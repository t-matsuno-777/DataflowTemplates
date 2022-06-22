/*
 * Copyright (C) 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.io;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import org.apache.avro.Conversion;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.reflect.ReflectData;
import org.apache.avro.specific.SpecificData;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.AvroIO.Sink;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.schemas.utils.AvroUtils;
import org.joda.time.ReadableInstant;

/**
 * This sink sets the Avro logical type conversion {@link JodaDateTimeOrLongConversion} for the
 * logical type {@link LogicalTypes.TimestampMillis}, and delegates all actual data writing work to
 * {@link AvroIO.Sink<ElementT>}.
 *
 * <p>The added conversion is to allow writing of {@link org.apache.avro.generic.GenericRecord}
 * generated by both: separate Java processes where static initialization of {@link
 * org.apache.beam.sdk.schemas.utils.AvroUtils} has been and has not been executed.
 *
 * <p>The processes that use {@link org.apache.beam.sdk.schemas.utils.AvroUtils} produce generic
 * records with Avro logical type {@link JodaDateTimeOrLongConversion} being represented by {@link
 * ReadableInstant}, and processes that don't use it produce records with with Avro logical type
 * {@link JodaDateTimeOrLongConversion} being represented by {@link Long}.
 */
public class AvroSinkWithJodaDatesConversion<ElementT extends IndexedRecord>
    implements FileIO.Sink<ElementT> {

  static {
    // Call any AvroUtils method to force AvroUtils initialization to ensure that AvroUtils static
    // init runs before this static init deterministically.
    AvroUtils.schemaCoder(Object.class);

    // override type conversion that was done by AvroUtils
    SpecificData.get().addLogicalTypeConversion(JodaDateTimeOrLongConversion.INSTANCE);
    GenericData.get().addLogicalTypeConversion(JodaDateTimeOrLongConversion.INSTANCE);
    ReflectData.get().addLogicalTypeConversion(JodaDateTimeOrLongConversion.INSTANCE);
  }

  private final AvroIO.Sink<ElementT> sink;

  public AvroSinkWithJodaDatesConversion(Schema schema) {
    sink = AvroIO.sink(schema);
  }

  public Sink<ElementT> withCodec(CodecFactory codec) {
    return sink.withCodec(codec);
  }

  public void open(WritableByteChannel channel) throws IOException {
    sink.open(channel);
  }

  public void write(ElementT element) throws IOException {
    sink.write(element);
  }

  public void flush() throws IOException {
    sink.flush();
  }

  /** The conversion that can handle both {@link Long} and {@link ReadableInstant}. */
  private static final class JodaDateTimeOrLongConversion extends Conversion<Object> {

    static final JodaDateTimeOrLongConversion INSTANCE = new JodaDateTimeOrLongConversion();

    @Override
    public Class<Object> getConvertedType() {
      return Object.class;
    }

    @Override
    public String getLogicalTypeName() {
      return LogicalTypes.timestampMillis().getName();
    }

    @Override
    public Object fromLong(Long value, Schema schema, LogicalType type) {
      return value;
    }

    @Override
    public Long toLong(Object value, Schema schema, LogicalType type) {
      if (value instanceof ReadableInstant) {
        return ((ReadableInstant) value).getMillis();
      } else if (value instanceof Long) {
        return (Long) value;
      } else {
        throw new IllegalStateException(
            String.format(
                "The value of the logical-type %s is of incompatible class %s",
                type.getName(), value.getClass().getName()));
      }
    }
  }
}
