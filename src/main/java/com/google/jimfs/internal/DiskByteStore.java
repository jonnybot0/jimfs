/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jimfs.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndexes;
import static com.google.jimfs.internal.Disk.BlockQueue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Byte store backed by a {@link Disk}.
 *
 * @author Colin Decker
 */
final class DiskByteStore extends ByteStore {

  private final Disk disk;
  private final BlockQueue blocks;
  private long size;

  public DiskByteStore(Disk disk) {
    this(disk, new BlockQueue(32), 0);
  }

  private DiskByteStore(Disk disk, BlockQueue blocks, long size) {
    this.disk = checkNotNull(disk);
    this.blocks = blocks;
    this.size = size;
  }

  @Override
  public long size() {
    return size;
  }

  @Override
  protected ByteStore createCopy() {
    BlockQueue copyBlocks = new BlockQueue(blocks.size() * 2);
    for (int i = 0; i < blocks.size(); i++) {
      int block = blockForRead(i);
      int copy = disk.alloc();
      disk.copy(block, copy);
      copyBlocks.add(copy);
    }
    return new DiskByteStore(disk, copyBlocks, size);
  }

  @Override
  public void delete() {
    writeLock().lock();
    try {
      disk.free(blocks);
      blocks.clear();
      size = 0;
    } finally {
      writeLock().unlock();
    }
  }

  @Override
  public boolean truncate(long size) {
    if (size >= this.size) {
      return false;
    }

    long lastPosition = size - 1;
    this.size = size;

    int newBlockCount = blockIndex(lastPosition) + 1;
    int blocksToRemove = blocks.size() - newBlockCount;
    if (blocksToRemove > 0) {
      BlockQueue blocksToFree = new BlockQueue(blocksToRemove);
      for (int i = 0; i < blocksToRemove; i++) {
        blocksToFree.add(blocks.take()); // removing blocks from the end
      }
      disk.free(blocksToFree);
    }

    return true;
  }

  /**
   * If pos is greater than the current size of this store, zeroes bytes between the current size
   * and pos and sets size to pos. New blocks are added to the file if necessary.
   */
  private void zeroForWrite(long pos) {
    if (pos <= size) {
      return;
    }

    long remaining = pos - size;

    int blockIndex = blockIndex(size);
    int block = blockForWrite(blockIndex);
    int off = offsetInBlock(size);
    int len = (int) Math.min(disk.blockSize() - off, remaining);

    disk.zero(block, off, len);

    remaining -= len;

    while (remaining > 0) {
      block = blockForWrite(++blockIndex);
      len = (int) Math.min(disk.blockSize(), remaining);

      disk.zero(block, 0, len);

      remaining -= len;
    }

    size = pos;
  }

  @Override
  public int write(long pos, byte b) {
    checkNotNegative(pos, "pos");

    zeroForWrite(pos);

    int block = blockForWrite(blockIndex(pos));
    int off = offsetInBlock(pos);
    disk.put(block, off, b);

    if (pos >= size) {
      size = pos + 1;
    }

    return 1;
  }

  @Override
  public int write(long pos, byte[] b, int off, int len) {
    checkNotNegative(pos, "pos");
    checkPositionIndexes(off, off + len, b.length);

    zeroForWrite(pos);

    if (len == 0) {
      return 0;
    }

    int remaining = len;

    int blockIndex = blockIndex(pos);
    int block = blockForWrite(blockIndex);
    int offInBlock = offsetInBlock(pos);
    int writeLen = Math.min(disk.blockSize() - offInBlock, remaining);

    disk.put(block, offInBlock, b, off, writeLen);

    remaining -= writeLen;
    off += writeLen;

    while (remaining > 0) {
      block = blockForWrite(++blockIndex);
      writeLen = Math.min(disk.blockSize(), remaining);

      disk.put(block, 0, b, off, writeLen);

      remaining -= writeLen;
      off += writeLen;
    }

    long newPos = pos + len;
    if (newPos > size) {
      size = newPos;
    }

    return len;
  }

  @Override
  public int write(long pos, ByteBuffer buf) {
    checkNotNegative(pos, "pos");

    zeroForWrite(pos);

    if (!buf.hasRemaining()) {
      return 0;
    }

    int bytesToWrite = buf.remaining();

    int blockIndex = blockIndex(pos);
    int block = blockForWrite(blockIndex);
    int off = offsetInBlock(pos);

    disk.put(block, off, buf);

    while (buf.hasRemaining()) {
      block = blockForWrite(++blockIndex);

      disk.put(block, 0, buf);
    }

    if (pos + bytesToWrite > size) {
      size = pos + bytesToWrite;
    }

    return bytesToWrite;
  }

  @Override
  public long transferFrom(ReadableByteChannel src, long pos, long count) throws IOException {
    checkNotNegative(pos, "pos");
    checkNotNegative(count, "count");

    zeroForWrite(pos);

    if (count == 0) {
      return 0;
    }

    long remaining = count;

    int blockIndex = blockIndex(pos);
    int block = blockForWrite(blockIndex);
    int off = offsetInBlock(pos);

    ByteBuffer buf = disk.asByteBuffer(block, off, remaining);

    int read = 0;
    while (buf.hasRemaining()) {
      read = src.read(buf);
      if (read == -1) {
        break;
      }

      remaining -= read;
    }

    if (read != -1) {
      outer: while (remaining > 0) {
        block = blockForWrite(++blockIndex);

        buf = disk.asByteBuffer(block, 0, remaining);
        while (buf.hasRemaining()) {
          read = src.read(buf);
          if (read == -1) {
            break outer;
          }

          remaining -= read;
        }
      }
    }

    long written = count - remaining;
    long newPos = pos + written;
    if (newPos > size) {
      size = newPos;
    }

    return written;
  }

  @Override
  public int read(long pos) {
    checkNotNegative(pos, "pos");

    if (pos >= size) {
      return -1;
    }

    int block = blockForRead(blockIndex(pos));
    int off = offsetInBlock(pos);
    return disk.get(block, off);
  }

  @Override
  public int read(long pos, byte[] b, int off, int len) {
    checkNotNegative(pos, "pos");
    checkPositionIndexes(off, off + len, b.length);

    int bytesToRead = bytesToRead(pos, len);

    if (bytesToRead > 0) {
      int remaining = bytesToRead;

      int blockIndex = blockIndex(pos);
      int block = blockForRead(blockIndex);
      int offsetInBlock = offsetInBlock(pos);

      int readLen = Math.min(disk.blockSize() - offsetInBlock, remaining);

      disk.get(block, offsetInBlock, b, off, readLen);

      remaining -= readLen;
      off += readLen;

      while (remaining > 0) {
        block = blockForRead(++blockIndex);

        readLen = Math.min(disk.blockSize(), remaining);

        disk.get(block, 0, b, off, readLen);

        remaining -= readLen;
        off += readLen;
      }
    }

    return bytesToRead;
  }

  @Override
  public int read(long pos, ByteBuffer buf) {
    checkNotNegative(pos, "pos");

    int bytesToRead = bytesToRead(pos, buf.remaining());

    if (bytesToRead > 0) {
      int remaining = bytesToRead;

      int blockIndex = blockIndex(pos);
      int block = blockForRead(blockIndex);
      int off = offsetInBlock(pos);

      remaining -= disk.get(block, off, buf, remaining);

      while (remaining > 0) {
        block = blockForRead(++blockIndex);
        remaining -= disk.get(block, 0, buf, remaining);
      }
    }

    return bytesToRead;
  }

  @Override
  public long transferTo(long pos, long count, WritableByteChannel dest) throws IOException {
    checkNotNegative(pos, "pos");
    checkNotNegative(count, "count");

    long bytesToRead = bytesToRead(pos, count);

    if (bytesToRead > 0) {
      long remaining = bytesToRead;

      int blockIndex = blockIndex(pos);
      int block = blockForRead(blockIndex);
      int off = offsetInBlock(pos);

      ByteBuffer buf = disk.asByteBuffer(block, off, remaining);
      while (buf.hasRemaining()) {
        remaining -= dest.write(buf);
      }

      while (remaining > 0) {
        block = blockForRead(++blockIndex);

        buf = disk.asByteBuffer(block, 0, remaining);
        while (buf.hasRemaining()) {
          remaining -= dest.write(buf);
        }
      }
    }

    return Math.max(bytesToRead, 0); // don't return -1 for this method
  }

  /**
   * Gets the block at the given index.
   */
  private int blockForRead(int index) {
    return blocks.get(index);
  }

  /**
   * Gets the block at the given index, expanding to create the block if necessary.
   */
  private int blockForWrite(int index) {
    int additionalBlocksNeeded = index - blocks.size() + 1;
    expandBlocks(additionalBlocksNeeded);

    return blocks.get(index);
  }

  private void expandBlocks(int additionalBlocksNeeded) {
    disk.alloc(blocks, additionalBlocksNeeded);
  }

  private int blockIndex(long position) {
    return (int) (position / disk.blockSize());
  }

  private int offsetInBlock(long position) {
    return (int) (position % disk.blockSize());
  }

  /**
   * Returns the number of bytes that can be read starting at position {@code pos} (up to a maximum
   * of {@code max}) or -1 if {@code pos} is greater than or equal to the current size.
   */
  private long bytesToRead(long pos, long max) {
    long available = size - pos;
    if (available <= 0) {
      return -1;
    }
    return Math.min(available, max);
  }

  /**
   * Returns the number of bytes that can be read starting at position {@code pos} (up to a maximum
   * of {@code max}) or -1 if {@code pos} is greater than or equal to the current size.
   */
  private int bytesToRead(long pos, int max) {
    return (int) bytesToRead(pos, (long) max);
  }
}