/*
 * Copyright (c) 2020-2021, NVIDIA CORPORATION.
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

package ai.rapids.cudf.nvcomp;

import ai.rapids.cudf.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;

public class NvcompTest {
  private static final Logger log = LoggerFactory.getLogger(ColumnVector.class);

  @Test
  void testLZ4RoundTripViaLZ4DecompressorSync() {
    lz4RoundTrip(false);
  }

  @Test
  void testLZ4RoundTripViaLZ4DecompressorAsync() {
    lz4RoundTrip(true);
  }

  @Test
  void testBatchedLZ4RoundTripAsync() {
    final Cuda.Stream stream = Cuda.DEFAULT_STREAM;
    final long chunkSize = 64 * 1024;
    final long targetIntermediteSize = Long.MAX_VALUE;
    final int maxElements = 1024 * 1024 + 1;
    final int numBuffers = 200;
    long[] data = new long[maxElements];
    for (int i = 0; i < maxElements; ++i) {
      data[i] = i;
    }

    try (CloseableArray<DeviceMemoryBuffer> originalBuffers =
             CloseableArray.wrap(new DeviceMemoryBuffer[numBuffers])) {
      // create the batched buffers to compress
      for (int i = 0; i < originalBuffers.size(); i++) {
        originalBuffers.set(i, initBatchBuffer(data, i));
        // Increment the refcount since compression will try to close it
        originalBuffers.get(i).incRefCount();
      }

      // compress and decompress the buffers
      BatchedLZ4Compressor compressor = new BatchedLZ4Compressor(chunkSize, targetIntermediteSize);

      try (CloseableArray<DeviceMemoryBuffer> compressedBuffers =
               CloseableArray.wrap(compressor.compress(originalBuffers.getArray(), stream));
           CloseableArray<DeviceMemoryBuffer> uncompressedBuffers =
               CloseableArray.wrap(new DeviceMemoryBuffer[numBuffers])) {
        for (int i = 0; i < numBuffers; i++) {
          uncompressedBuffers.set(i,
              DeviceMemoryBuffer.allocate(originalBuffers.get(i).getLength()));
        }

        // decompress takes ownership of the compressed buffers and will close them
        BatchedLZ4Decompressor.decompressAsync(chunkSize, compressedBuffers.release(),
            uncompressedBuffers.getArray(), stream);

        // check the decompressed results against the original
        for (int i = 0; i < numBuffers; ++i) {
          try (HostMemoryBuffer expected =
                   HostMemoryBuffer.allocate(originalBuffers.get(i).getLength());
               HostMemoryBuffer actual =
                   HostMemoryBuffer.allocate(uncompressedBuffers.get(i).getLength())) {
            Assertions.assertTrue(expected.getLength() <= Integer.MAX_VALUE);
            Assertions.assertTrue(actual.getLength() <= Integer.MAX_VALUE);
            Assertions.assertEquals(expected.getLength(), actual.getLength(),
                "uncompressed size mismatch at buffer " + i);
            expected.copyFromDeviceBuffer(originalBuffers.get(i));
            actual.copyFromDeviceBuffer(uncompressedBuffers.get(i));
            byte[] expectedBytes = new byte[(int) expected.getLength()];
            expected.getBytes(expectedBytes, 0, 0, expected.getLength());
            byte[] actualBytes = new byte[(int) actual.getLength()];
            actual.getBytes(actualBytes, 0, 0, actual.getLength());
            Assertions.assertArrayEquals(expectedBytes, actualBytes,
                "mismatch in batch buffer " + i);
          }
        }
      }
    }
  }

  private void closeBuffer(MemoryBuffer buffer) {
    if (buffer != null) {
      buffer.close();
    }
  }

  private DeviceMemoryBuffer initBatchBuffer(long[] data, int bufferId) {
    // grab a subsection of the data based on buffer ID
    int dataStart = 0;
    int dataLength = data.length / (bufferId + 1);
    switch (bufferId % 3) {
      case 0:
        // take a portion of the first half
        dataLength /= 2;
        break;
      case 1:
        // take a portion of the last half
        dataStart = data.length / 2;
        dataLength /= 2;
        break;
      default:
        break;
    }
    long[] bufferData = Arrays.copyOfRange(data, dataStart, dataStart + dataLength + 1);
    DeviceMemoryBuffer devBuffer = null;
    try (HostMemoryBuffer hmb = HostMemoryBuffer.allocate(bufferData.length * 8)) {
      hmb.setLongs(0, bufferData, 0, bufferData.length);
      devBuffer = DeviceMemoryBuffer.allocate(hmb.getLength());
      devBuffer.copyFromHostBuffer(hmb);
      return devBuffer;
    } catch (Throwable t) {
      closeBuffer(devBuffer);
      throw new RuntimeException(t);
    }
  }

  private void lz4RoundTrip(boolean useAsync) {
    final Cuda.Stream stream = Cuda.DEFAULT_STREAM;
    final long chunkSize = 64 * 1024;
    final int numElements = 10 * 1024 * 1024 + 1;
    long[] data = new long[numElements];
    for (int i = 0; i < numElements; ++i) {
      data[i] = i;
    }

    DeviceMemoryBuffer tempBuffer = null;
    DeviceMemoryBuffer compressedBuffer = null;
    DeviceMemoryBuffer uncompressedBuffer = null;
    try (ColumnVector v = ColumnVector.fromLongs(data)) {
      BaseDeviceMemoryBuffer inputBuffer = v.getDeviceBufferFor(BufferType.DATA);
      final long uncompressedSize = inputBuffer.getLength();
      log.debug("Uncompressed size is {}", uncompressedSize);

      LZ4Compressor.Configuration compressConf =
          LZ4Compressor.configure(chunkSize, uncompressedSize);
      Assertions.assertTrue(compressConf.getMetadataBytes() > 0);
      log.debug("Using {} temporary space for lz4 compression", compressConf.getTempBytes());
      tempBuffer = DeviceMemoryBuffer.allocate(compressConf.getTempBytes());
      log.debug("lz4 compressed size estimate is {}", compressConf.getMaxCompressedBytes());

      compressedBuffer = DeviceMemoryBuffer.allocate(compressConf.getMaxCompressedBytes());

      long startTime = System.nanoTime();
      long compressedSize;
      if (useAsync) {
        try (DeviceMemoryBuffer devCompressedSizeBuffer = DeviceMemoryBuffer.allocate(8);
             HostMemoryBuffer hostCompressedSizeBuffer = HostMemoryBuffer.allocate(8)) {
          LZ4Compressor.compressAsync(devCompressedSizeBuffer, inputBuffer, CompressionType.CHAR,
              chunkSize, tempBuffer, compressedBuffer, stream);
          hostCompressedSizeBuffer.copyFromDeviceBufferAsync(devCompressedSizeBuffer, stream);
          stream.sync();
          compressedSize = hostCompressedSizeBuffer.getLong(0);
        }
      } else {
        compressedSize = LZ4Compressor.compress(inputBuffer, CompressionType.CHAR, chunkSize,
            tempBuffer, compressedBuffer, stream);
      }
      double duration = (System.nanoTime() - startTime) / 1000.0;
      log.info("Compressed with lz4 to {} in {} us", compressedSize, duration);

      tempBuffer.close();
      tempBuffer = null;

      try (LZ4Decompressor.Configuration decompressConf =
               LZ4Decompressor.configure(compressedBuffer, stream)) {
        final long tempSize = decompressConf.getTempBytes();

        log.debug("Using {} temporary space for lz4 compression", tempSize);
        tempBuffer = DeviceMemoryBuffer.allocate(tempSize);

        final long outSize = decompressConf.getUncompressedBytes();
        Assertions.assertEquals(inputBuffer.getLength(), outSize);

        uncompressedBuffer = DeviceMemoryBuffer.allocate(outSize);

        LZ4Decompressor.decompressAsync(compressedBuffer, decompressConf, tempBuffer,
            uncompressedBuffer, stream);

        try (ColumnVector v2 = new ColumnVector(
            DType.INT64,
            numElements,
            Optional.empty(),
            uncompressedBuffer,
            null,
            null);
             HostColumnVector hv2 = v2.copyToHost()) {
          uncompressedBuffer = null;
          for (int i = 0; i < numElements; ++i) {
            long val = hv2.getLong(i);
            if (val != i) {
              Assertions.fail("Expected " + i + " at " + i + " found " + val);
            }
          }
        }
      }
    } finally {
      closeBuffer(tempBuffer);
      closeBuffer(compressedBuffer);
      closeBuffer(uncompressedBuffer);
    }
  }
}
