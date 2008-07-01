package org.apache.solr.schema;
/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.document.Fieldable;
import org.apache.lucene.search.SortField;

import org.apache.solr.request.TextResponseWriter;
import org.apache.solr.request.XMLWriter;
import org.apache.solr.search.function.ValueSource;
import org.apache.solr.search.function.ByteFieldSource;

import java.io.IOException;
import java.util.Map;

/**
 * @version $Id: LongField.java 555343 2007-07-11 17:46:25Z hossman $
 */
public class ByteField extends FieldType {
  protected void init(IndexSchema schema, Map<String, String> args) {
    restrictProps(SORT_MISSING_FIRST | SORT_MISSING_LAST);
  }

  /////////////////////////////////////////////////////////////
  public SortField getSortField(SchemaField field, boolean reverse) {
    return new SortField(field.name, SortField.BYTE, reverse);
  }

  public ValueSource getValueSource(SchemaField field) {
    return new ByteFieldSource(field.name);
  }


  public void write(XMLWriter xmlWriter, String name, Fieldable f) throws IOException {
    xmlWriter.writeByte(name, f.stringValue());
  }

  public void write(TextResponseWriter writer, String name, Fieldable f) throws IOException {
    writer.writeByte(name, f.stringValue());
  }

  @Override
  public Byte toObject(Fieldable f) {
    return Byte.valueOf(toExternal(f));
  }
}