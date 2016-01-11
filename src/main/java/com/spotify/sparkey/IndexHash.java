/*
 * Copyright (c) 2011-2013 Spotify AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.spotify.sparkey;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.primitives.UnsignedLongs;

import java.io.*;
import java.util.Random;

final class IndexHash {
  private final File indexFile;
  final IndexHeader header;
  private final File logFile;
  private final ReadOnlyMemMap indexData;

  private final int hashSeed;
  private final long hashCapacity;
  private final BlockRandomInput logData;
  private final byte[] keyBuf;
  private final SafeStream stream;

  private final IndexHashEntry entry;
  private final int indexStart;
  private final int slotSize;
  final LogHeader logHeader;
  private final int maxBlockSize;
  private final int entryBlockBits;
  private final int entryBlockBitmask;

  private IndexHash(File indexFile, File logFile, IndexHeader header, LogHeader logHeader, ReadOnlyMemMap indexData, int maxBlockSize, BlockRandomInput logData) {
    this.indexFile = indexFile;
    this.logFile = logFile;
    this.header = header;
    this.logHeader = logHeader;
    this.indexData = indexData;
    this.maxBlockSize = maxBlockSize;
    this.logData = logData;

    indexStart = header.size();
    hashSeed = header.getHashSeed();
    hashCapacity = header.getHashCapacity();

    int maxKeyLen = (int) header.getMaxKeyLen();
    keyBuf = new byte[Math.max(maxKeyLen, 1024)];
    slotSize = header.getSlotSize();
    entry = new IndexHashEntry();
    entryBlockBits = header.getEntryBlockBits();
    entryBlockBitmask = ((1 << entryBlockBits) - 1);

    stream = new SafeStream(logData);
  }

  static IndexHash open(File indexFile, File logFile) throws IOException {
    IndexHeader header = IndexHeader.read(indexFile);
    LogHeader logHeader = LogHeader.read(logFile);
    verifyIdentifier(logHeader, header);

    if (header.getDataEnd() > logHeader.getDataEnd()) {
      throw new IOException("Corrupt index file '" + indexFile.toString() + "': referencing more data than exists in the log file");
    }

    ReadOnlyMemMap indexData = null;
    BlockRandomInput logData = null;
    IndexHash indexHash = null;
    try {
      int maxBlockSize = 0;
      indexData = new ReadOnlyMemMap(indexFile);
      maxBlockSize = logHeader.getCompressionBlockSize();
      logData = logHeader.getCompressionType().createRandomAccessData(new ReadOnlyMemMap(logFile),
              maxBlockSize);

      indexHash = new IndexHash(indexFile, logFile, header, logHeader, indexData, maxBlockSize, logData);
      indexHash.validate();
      return indexHash;
    } catch (Exception e) {
      if (indexHash != null) {
        indexHash.close();
      } else {
        if (indexData != null) {
          indexData.close();
        }
        if (logData != null) {
          logData.close();
        }
      }
      Throwables.propagateIfPossible(e, IOException.class);
      throw Throwables.propagate(e);
    }
  }

  private void validate() {
    long expectedFileSize = IndexHeader.HEADER_SIZE + slotSize * hashCapacity;
    if (expectedFileSize != indexFile.length()) {
      throw new RuntimeException("Corrupt index file - incorrect size. Expected " + expectedFileSize + " but was " + indexFile.length());
    }
  }

  private static int calcEntryBlockBits(int maxEntriesPerBlock) {
    int i = 0;
    while ((1 << i) < maxEntriesPerBlock) {
      i++;
    }
    return i;
  }

  static void createNew(File indexFile, File logFile, HashType hashType, double sparsity, boolean fsync) throws IOException {
    if (sparsity < 1.3) {
      sparsity = 1.3;
    }
    LogHeader logHeader = LogHeader.read(logFile);

    int addressSize = calcAddressSize(logHeader) ? 4 : 8;
    if (hashType == null) {
      hashType = logHeader.getNumPuts() < (1 << 23) ? HashType.HASH_32_BITS : HashType.HASH_64_BITS;
    }

    long capacity = 1L | (long) (logHeader.getNumPuts() * sparsity);

    IndexHeader header = new IndexHeader(logHeader.getFileIdentifier(), logHeader.getDataEnd(),
            logHeader.getMaxKeyLen(), logHeader.getMaxValueLen(), addressSize, hashType.size(), capacity, logHeader.getNumPuts(), new Random().nextInt(),
            calcEntryBlockBits(logHeader.getMaxEntriesPerBlock()));

    long hashLength = header.getHashLength();

    InMemoryData indexData = new InMemoryData(hashLength);

    fillFromLog(indexData, logFile, header, logHeader.size(), header.getDataEnd(),
            logHeader);
    calculateMaxDisplacement(header, indexData);
    flushToFile(indexFile, header, indexData, fsync);
  }

  private static void calculateMaxDisplacement(IndexHeader header, InMemoryData indexData) throws IOException {
    HashType hashData = header.getHashType();
    AddressSize addressData = header.getAddressData();

    long pos = 0;
    long capacity = header.getHashCapacity();

    long maxDisplacement = 0;
    long numHashCollisions = 0;
    long totalDisplacement = 0;

    boolean hasFirst = false;
    long firstHash = 0;

    boolean hasLast = false;
    long lastHash = 0;

    boolean hasPrev = false;
    long prevHash = -1;
    for (long slot = 0; slot < capacity; slot++) {
      long hash = hashData.readHash(indexData, pos);
      if (hasPrev && prevHash == hash) {
        numHashCollisions++;
      }
      long position = addressData.readAddress(indexData, pos + hashData.size());
      if (position != 0) {
        prevHash = hash;
        hasPrev = true;
        long displacement = getDisplacement(capacity, slot, hash);
        totalDisplacement += displacement;
        maxDisplacement = Math.max(maxDisplacement, displacement);
        if (slot == 0) {
          firstHash = hash;
          hasFirst = true;
        }
        if (slot == capacity - 1) {
          lastHash = hash;
          hasLast = true;
        }
      } else {
        hasPrev = false;
      }
    }
    if (hasFirst && hasLast && firstHash == lastHash) {
      numHashCollisions++;
    }
    header.setTotalDisplacement(totalDisplacement);
    header.setMaxDisplacement(maxDisplacement);
    header.setHashCollisions(numHashCollisions);
  }

  private static boolean calcAddressSize(LogHeader logHeader) {
    int entryBlockBits = calcEntryBlockBits(logHeader.getMaxEntriesPerBlock());
    return logHeader.getDataEnd() <= (1L << (30 - entryBlockBits));
  }

  private static void flushToFile(File file, IndexHeader header, InMemoryData data, boolean fsync) throws IOException {
    FileOutputStream stream = new FileOutputStream(file);
    try {
      header.write(stream);
      data.flushToFile(stream);
      stream.flush(); // Not needed for FileOutputStream, but still semantically correct
      if (fsync) {
        stream.getFD().sync();
      }
    } finally {
      data.close();
      stream.close();
    }
  }

  void close() {
    this.indexData.close();
    this.logData.close();
  }

  private static void fillFromLog(InMemoryData indexData, File logFile, IndexHeader header, long start, long end, LogHeader logHeader) throws IOException {
    SparkeyLogIterator iterator = new SparkeyLogIterator(logFile, start, end);
    BlockRandomInput logData = logHeader.getCompressionType().createRandomAccessData(new ReadOnlyMemMap(logFile), logHeader.getCompressionBlockSize());

    HashType hashData = header.getHashType();
    AddressSize addressData = header.getAddressData();

    long hashCapacity = header.getHashCapacity();

    byte[] keyBuf = new byte[(int) header.getMaxKeyLen()];
    try {
      long prevBlock = -1;
      int entryIndex = 0;
      for (SparkeyReader.Entry entry2 : iterator) {
        // Safe cast, since the iterator is known to be a SparkeyLogIterator
        SparkeyLogIterator.Entry entry = (SparkeyLogIterator.Entry) entry2;
        long curBlock = entry.getPosition();
        if (curBlock != prevBlock) {
          prevBlock = curBlock;
          entryIndex = 0;
        } else {
          entryIndex++;
        }
        switch (entry.getType()) {
          case PUT:
            put(indexData, header, hashCapacity, entry.getKeyLength(), entry.getKeyBuf(), entry.getPosition(), entryIndex,
                    logData, keyBuf, hashData, addressData, header.getEntryBlockBitsBitmask(), header.getEntryBlockBits());
            break;
          case DELETE:
            delete(indexData, header, hashCapacity, entry.getKeyLength(), entry.getKeyBuf(), logData, keyBuf,
                    hashData, addressData, header.getEntryBlockBitsBitmask(), header.getEntryBlockBits());
            break;
        }
      }
    } finally {
      logData.close();
    }
  }

  private static void verifyIdentifier(LogHeader logHeader, IndexHeader header) {
    if (!(logHeader.getFileIdentifier() == header.getFileIdentifier())) {
      throw new IllegalArgumentException("Log file did not match index file");
    }
  }

  boolean isAt(int keyLen, byte[] key, long position, int entryIndex) throws IOException {
    HashType hashData = header.getHashType();
    AddressSize addressData = header.getAddressData();
    long hash = hashData.hash(keyLen, key, hashSeed);
    long wantedSlot = getWantedSlot(hash, hashCapacity);

    int start = indexStart;
    long pos = start + wantedSlot * slotSize;

    final int hashSize = hashData.size();

    long slot = wantedSlot;
    long displacement = 0;
    while (true) {
      long hash2 = hashData.readHash(indexData, pos);
      long position2 = addressData.readAddress(indexData, pos + hashSize);
      if (position2 == 0) {
        return false;
      }
      int entryIndex2 = (int) (position2) & entryBlockBitmask;
      position2 >>>= entryBlockBits;
      if (hash == hash2 && position2 == position && entryIndex == entryIndex2) {
        return true;
      }

      long otherDisplacement = getDisplacement(hashCapacity, slot, hash2);
      if (displacement > otherDisplacement) {
        return false;
      }

      pos += slotSize;
      displacement++;
      slot++;
      if (slot == hashCapacity) {
        pos = start;
        slot = 0;
      }
    }
  }

  SparkeyReader.Entry get(int keyLen, byte[] key) throws IOException {
    HashType hashData = header.getHashType();
    AddressSize addressData = header.getAddressData();
    long hash = hashData.hash(keyLen, key, hashSeed);
    long wantedSlot = getWantedSlot(hash, hashCapacity);

    int start = indexStart;
    long pos = start + wantedSlot * slotSize;

    final int hashSize = hashData.size();

    long slot = wantedSlot;
    long displacement = 0;

    while (true) {
      long hash2 = hashData.readHash(indexData, pos);
      long position2 = addressData.readAddress(indexData, pos + hashSize);
      if (position2 == 0) {
        return null;
      }
      int entryIndex = (int) (position2) & entryBlockBitmask;
      position2 >>>= entryBlockBits;
      if (hash == hash2) {
        logData.seek(position2);
        skipStuff(entryIndex, logData);
        int keyLen2 = Util.readUnsignedVLQInt(logData);
        if (keyLen2 == 0) {
          throw new RuntimeException("Invalid data - reference to delete entry");
        }
        keyLen2--;
        if (keyLen == keyLen2) {
          int valueLen2 = Util.readUnsignedVLQInt(logData);
          logData.readFully(keyBuf, 0, keyLen2);
          if (Util.equals(keyLen, key, keyBuf)) {
            entry.keyLen = keyLen2;
            entry.valueLen = valueLen2;
            stream.remaining = valueLen2;
            return entry;
          }
        }
      }
      long otherDisplacement = getDisplacement(hashCapacity, slot, hash2);
      if (displacement > otherDisplacement) {
        return null;
      }
      displacement++;
      slot++;
      pos += slotSize;
      if (slot == hashCapacity) {
        pos = start;
        slot = 0;
      }
    }
  }

  private static void delete(InMemoryData indexData, IndexHeader header, long hashCapacity, int keyLen,
                             byte[] key, BlockRandomInput logData,
                             byte[] keyBuf, HashType hashData, AddressSize addressData, int entryIndexBitmask, int entryIndexBits) throws IOException {
    long hash = hashData.hash(keyLen, key, header.getHashSeed());
    long wantedSlot = getWantedSlot(hash, hashCapacity);

    long pos = wantedSlot * header.getSlotSize();

    final int hashSize = hashData.size();

    long slot = wantedSlot;
    long displacement = 0;

    while (true) {
      long hash2 = hashData.readHash(indexData, pos);
      long position2 = addressData.readAddress(indexData, pos + hashSize);
      if (position2 == 0) {
        return;
      }
      int entryIndex = (int) (position2) & entryIndexBitmask;
      position2 >>>= entryIndexBits;
      if (hash == hash2) {
        logData.seek(position2);
        skipStuff(entryIndex, logData);
        int keyLen2 = Util.readUnsignedVLQInt(logData);
        if (keyLen2 == 0) {
          throw new RuntimeException("Invalid data - reference to delete entry");
        }
        keyLen2--;
        if (keyLen == keyLen2) {
          int valueLen2 = Util.readUnsignedVLQInt(logData);
          logData.readFully(keyBuf, 0, keyLen2);
          if (Util.equals(keyLen, key, keyBuf)) {

            // TODO: possibly optimize this to read and write stuff to move in chunks instead of one by one, to decrease number of seeks.
            while (true) {
              long nextSlot = (slot + 1) % hashCapacity;
              long nextSlotPos = nextSlot * header.getSlotSize();
              long hash3 = hashData.readHash(indexData, nextSlotPos);
              long position3 = addressData.readAddress(indexData, nextSlotPos + hashSize);

              if (position3 == 0) {
                break;
              }
              if (getWantedSlot(hash3, hashCapacity) == nextSlot) {
                break;
              }

              long slotPos = slot * header.getSlotSize();
              hashData.writeHash(hash3, indexData, slotPos);
              addressData.writeAddress(position3, indexData, slotPos + hashSize);

              slot = nextSlot;
            }

            long slotPos = slot * header.getSlotSize();
            hashData.writeHash(0, indexData, slotPos);
            addressData.writeAddress(0, indexData, slotPos + hashSize);
            header.deletedEntry(keyLen2, valueLen2);

            return;
          }
        }
      }
      long otherDisplacement = getDisplacement(hashCapacity, slot, hash2);
      if (displacement > otherDisplacement) {
        return;
      }
      displacement++;
      slot++;
      pos += header.getSlotSize();
      if (slot == hashCapacity) {
        pos = 0;
        slot = 0;
      }
    }
  }

  private static void skipStuff(long entryIndex, BlockRandomInput logData) throws IOException {
    for (int i = 0; i < entryIndex; i++) {
      int keyLen2 = Util.readUnsignedVLQInt(logData);
      int valueLen2 = Util.readUnsignedVLQInt(logData);
      if (keyLen2 == 0) {
        logData.skipBytes(valueLen2);
      } else {
        logData.skipBytes(keyLen2 - 1 + valueLen2);
      }
    }
  }

  private static void put(InMemoryData indexData, IndexHeader header, long hashCapacity, int keyLen, byte[] key,
                          long position, int entryIndex, BlockRandomInput logData,
                          byte[] keyBuf, HashType hashData, AddressSize addressData, int entryIndexBitmask, int entryIndexBits) throws IOException {

    if (header.getNumEntries() >= hashCapacity) {
      throw new IOException("No free slots in the hash: " + header.getNumEntries() + " >= " + hashCapacity);
    }

    long hash = hashData.hash(keyLen, key, header.getHashSeed());
    long wantedSlot = getWantedSlot(hash, hashCapacity);

    final int slotSize = header.getSlotSize();
    long pos = wantedSlot * slotSize;

    long displacement = 0;
    long tries = hashCapacity;
    long slot = wantedSlot;

    final int hashSize = hashData.size();

    boolean mightBeCollision = true;
    while (--tries >= 0) {
      long hash2 = hashData.readHash(indexData, pos);
      long position2 = addressData.readAddress(indexData, pos + hashSize);
      if (position2 == 0) {
        hashData.writeHash(hash, indexData, pos);
        addressData.writeAddress((position << entryIndexBits) | entryIndex, indexData, pos + hashSize);
        header.addedEntry();
        return;
      }

      int entryIndex2 = (int) (position2) & entryIndexBitmask;
      position2 >>>= entryIndexBits;


      if (mightBeCollision && hash == hash2) {
        logData.seek(position2);
        skipStuff(entryIndex2, logData);
        int keyLen2 = Util.readUnsignedVLQInt(logData);
        int valueLen2 = Util.readUnsignedVLQInt(logData);
        if (keyLen2 == 0) {
          throw new RuntimeException("Invalid data - reference to delete entry");
        }
        keyLen2--;
        if (keyLen == keyLen2) {
          logData.readFully(keyBuf, 0, keyLen2);
          if (Util.equals(keyLen, key, keyBuf)) {
            hashData.writeHash(hash, indexData, pos);
            addressData.writeAddress((position << entryIndexBits) | entryIndex, indexData, pos + hashSize);
            header.replacedEntry(keyLen2, valueLen2);
            return;
          }
        }
      }

      long otherDisplacement = getDisplacement(hashCapacity, slot, hash2);
      if (displacement > otherDisplacement) {
        // Steal the slot, and move the other one
        hashData.writeHash(hash, indexData, pos);
        addressData.writeAddress((position << entryIndexBits) | entryIndex, indexData, pos + hashSize);

        position = position2;
        entryIndex = entryIndex2;
        displacement = otherDisplacement;
        hash = hash2;
        mightBeCollision = false;
      }

      pos += slotSize;
      displacement++;
      slot++;
      if (slot >= hashCapacity) {
        pos = 0;
        slot = 0;
      }
    }
    throw new IOException("No free slots in the hash");
  }

  private static long getWantedSlot(long hash, long capacity) {
    return UnsignedLongs.remainder(hash, capacity);
  }

  private static long getDisplacement(long capacity, long slot, long hash) {
    long displacement = slot - getWantedSlot(hash, capacity);
    if (displacement >= 0) {
      return displacement;
    } else {
      return displacement + capacity;
    }
  }

  IndexHash duplicate() {
    return new IndexHash(indexFile, logFile, header, logHeader, indexData, maxBlockSize, logData.duplicate());
  }

  private class IndexHashEntry implements SparkeyReader.Entry {
    private int keyLen;
    private long valueLen;

    @Override
    public int getKeyLength() {
      return keyLen;
    }

    @Override
    public byte[] getKey() {
      if (keyBuf.length == keyLen) {
        return keyBuf;
      }
      byte[] key = new byte[keyLen];
      System.arraycopy(keyBuf, 0, key, 0, keyLen);
      return key;
    }

    @Override
    public String getKeyAsString() {
      return new String(keyBuf, 0, keyLen, Charsets.UTF_8);
    }

    @Override
    public long getValueLength() {
      return valueLen;
    }

    @Override
    public String getValueAsString() throws IOException {
      return new String(getValue(), Charsets.UTF_8);
    }

    @Override
    public InputStream getValueAsStream() {
      return stream;
    }

    @Override
    public byte[] getValue() throws IOException {
      if (valueLen > Integer.MAX_VALUE) {
        throw new IllegalStateException("Value size is " + valueLen + " bytes, can't store in byte[]");
      }
      return readChunk((int) valueLen);
    }

    private byte[] readChunk(int size) throws IOException {
      byte[] res = new byte[size];
      stream.read(res);
      return res;
    }


    @Override
    public SparkeyReader.Type getType() {
      return SparkeyReader.Type.PUT;
    }
  }

  static class SafeStream extends InputStream {
    private final BlockRandomInput stream;
    private long remaining;

    private SafeStream(BlockRandomInput stream) {
      this.stream = stream;
    }

    @Override
    public int read() throws IOException {
      if (remaining >= 0) {
        remaining--;
        return stream.readUnsignedByte();
      }
      throw new EOFException();
    }

    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (len < 0) {
        throw new IllegalArgumentException();
      }
      if (remaining >= len) {
        remaining -= len;
        stream.readFully(b, off, len);
        return len;
      }
      throw new EOFException();
    }

    @Override
    public long skip(long n) throws IOException {
      if (n < 0) {
        throw new IllegalArgumentException();
      }
      if (remaining >= n) {
        stream.skipBytes(n);
        remaining -= n;
        return n;
      }
      throw new EOFException();
    }

    @Override
    public int available() throws IOException {
      if (remaining >= Integer.MAX_VALUE) {
        return Integer.MAX_VALUE;
      }
      return (int) remaining;
    }

    @Override
    public void close() {
      stream.close();
      try {
        super.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    @Override
    public void mark(int readlimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reset() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean markSupported() {
      return false;
    }
  }
}
