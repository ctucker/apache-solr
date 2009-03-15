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
package org.apache.solr;

import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.DateField;
import org.apache.solr.util.AbstractSolrTestCase;
import org.apache.solr.util.DateMathParser;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tests for TrieField functionality
 *
 * @version $Id$
 * @since solr 1.4
 */
public class TestTrie extends AbstractSolrTestCase {
  public String getSchemaFile() {
    return "schema-trie.xml";
  }

  public String getSolrConfigFile() {
    return "solrconfig.xml";
  }

  public void testTrieIntRangeSearch() throws Exception {
    for (int i = 0; i < 10; i++) {
      assertU(adoc("id", String.valueOf(i), "tint", String.valueOf(i)));
    }
    assertU(commit());
    assertQ("Range filter must match only 5 documents", req("q", "*:*", "fq", "tint:[2 TO 6]"), "//*[@numFound='5']");
    for (int i = 1; i < 11; i++) {
      assertU(adoc("id", String.valueOf(-i), "tint", String.valueOf(-i)));
    }
    assertU(commit());
    assertQ("Range filter must match only 5 documents", req("q", "*:*", "fq", "tint:[-6 TO -2]"), "//*[@numFound='5']");

    // Test open ended range searches
    assertQ("Range filter tint:[-9 to *] must match 20 documents", req("q", "*:*", "fq", "tint:[-10 TO *]"), "//*[@numFound='20']");
    assertQ("Range filter tint:[* to 9] must match 20 documents", req("q", "*:*", "fq", "tint:[* TO 10]"), "//*[@numFound='20']");
    assertQ("Range filter tint:[* to *] must match 20 documents", req("q", "*:*", "fq", "tint:[* TO *]"), "//*[@numFound='20']");
  }

  public void testTrieTermQuery() throws Exception {
    for (int i = 0; i < 10; i++) {
      assertU(adoc("id", String.valueOf(i),
              "tint", String.valueOf(i),
              "tfloat", String.valueOf(i * i * 31.11f),
              "tlong", String.valueOf((long) Integer.MAX_VALUE + (long) i),
              "tdouble", String.valueOf(i * 2.33d)));
    }
    assertU(commit());

    // Use with q
    assertQ("Term query on trie int field must match 1 document", req("q", "tint:2"), "//*[@numFound='1']");
    assertQ("Term query on trie float field must match 1 document", req("q", "tfloat:124.44"), "//*[@numFound='1']");
    assertQ("Term query on trie long field must match 1 document", req("q", "tlong:2147483648"), "//*[@numFound='1']");
    assertQ("Term query on trie double field must match 1 document", req("q", "tdouble:4.66"), "//*[@numFound='1']");

    // Use with fq
    assertQ("Term query on trie int field must match 1 document", req("q", "*:*", "fq", "tint:2"), "//*[@numFound='1']");
    assertQ("Term query on trie float field must match 1 document", req("q", "*:*", "fq", "tfloat:124.44"), "//*[@numFound='1']");
    assertQ("Term query on trie long field must match 1 document", req("q", "*:*", "fq", "tlong:2147483648"), "//*[@numFound='1']");
    assertQ("Term query on trie double field must match 1 document", req("q", "*:*", "fq", "tdouble:4.66"), "//*[@numFound='1']");
  }

  public void testTrieFloatRangeSearch() throws Exception {
    for (int i = 0; i < 10; i++) {
      assertU(adoc("id", String.valueOf(i), "tfloat", String.valueOf(i * i * 31.11f)));
    }
    assertU(commit());
    SolrQueryRequest req = req("q", "*:*", "fq", "tfloat:[0 TO 2518.0]");
    assertQ("Range filter must match only 5 documents", req, "//*[@numFound='9']");
    req = req("q", "*:*", "fq", "tfloat:[0 TO *]");
    assertQ("Range filter must match 10 documents", req, "//*[@numFound='10']");
  }

  public void testTrieLongRangeSearch() throws Exception {
    for (long i = Integer.MAX_VALUE, c = 0; i < (long) Integer.MAX_VALUE + 10l; i++) {
      assertU(adoc("id", String.valueOf(c++), "tlong", String.valueOf(i)));
    }
    assertU(commit());
    String fq = "tlong:[" + Integer.MAX_VALUE + " TO " + (5l + Integer.MAX_VALUE) + "]";
    SolrQueryRequest req = req("q", "*:*", "fq", fq);
    assertQ("Range filter must match only 5 documents", req, "//*[@numFound='6']");
    assertQ("Range filter tlong:[* to *] must match 10 documents", req("q", "*:*", "fq", "tlong:[* TO *]"), "//*[@numFound='10']");
  }

  public void testTrieDoubleRangeSearch() throws Exception {
    for (long i = Integer.MAX_VALUE, c = 0; i < (long) Integer.MAX_VALUE + 10l; i++) {
      assertU(adoc("id", String.valueOf(c++), "tdouble", String.valueOf(i * 2.33d)));
    }
    assertU(commit());
    String fq = "tdouble:[" + Integer.MAX_VALUE * 2.33d + " TO " + (5l + Integer.MAX_VALUE) * 2.33d + "]";
    assertQ("Range filter must match only 5 documents", req("q", "*:*", "fq", fq), "//*[@numFound='6']");
    assertQ("Range filter tdouble:[* to *] must match 10 documents", req("q", "*:*", "fq", "tdouble:[* TO *]"), "//*[@numFound='10']");
  }

  public void testTrieDateRangeSearch() throws Exception {
    for (int i = 0; i < 10; i++) {
      assertU(adoc("id", String.valueOf(i), "tdate", "1995-12-31T23:" + (i < 10 ? "0" + i : i) + ":59.999Z"));
    }
    assertU(commit());
    SolrQueryRequest req = req("q", "*:*", "fq", "tdate:[1995-12-31T23:00:59.999Z TO 1995-12-31T23:04:59.999Z]");
    assertQ("Range filter must match only 5 documents", req, "//*[@numFound='5']");

    // Test open ended range searches
    assertQ("Range filter tint:[1995-12-31T23:00:59.999Z to *] must match 10 documents", req("q", "*:*", "fq", "tdate:[1995-12-31T23:00:59.999Z TO *]"), "//*[@numFound='10']");
    assertQ("Range filter tint:[* to 1995-12-31T23:09:59.999Z] must match 10 documents", req("q", "*:*", "fq", "tdate:[* TO 1995-12-31T23:09:59.999Z]"), "//*[@numFound='10']");
    assertQ("Range filter tint:[* to *] must match 10 documents", req("q", "*:*", "fq", "tdate:[* TO *]"), "//*[@numFound='10']");

    // Test date math syntax
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));

    assertU(delQ("*:*"));
    DateMathParser dmp = new DateMathParser(DateField.UTC, Locale.US);
    for (int i = 0; i < 10; i++) {
      // index 10 days starting with today
      String d = format.format(i == 0 ? dmp.parseMath("/DAY") : dmp.parseMath("/DAY+" + i + "DAYS"));
      assertU(adoc("id", String.valueOf(i), "tdate", d));
    }
    assertU(commit());
    assertQ("Range filter must match only 10 documents", req("q", "*:*", "fq", "tdate:[* TO *]"), "//*[@numFound='10']");
    req = req("q", "*:*", "fq", "tdate:[NOW/DAY TO NOW/DAY+5DAYS]");
    assertQ("Range filter must match only 6 documents", req, "//*[@numFound='6']");

    // Test Term Queries
    assertU(adoc("id", "11", "tdate", "1995-12-31T23:59:59.999Z"));
    assertU(commit());
    assertQ("Term query must match only 1 document", req("q", "tdate:1995-12-31T23\\:59\\:59.999Z"), "//*[@numFound='1']");
    assertQ("Term query must match only 1 document", req("q", "*:*", "fq", "tdate:1995-12-31T23\\:59\\:59.999Z"), "//*[@numFound='1']");
  }

  public void testTrieDoubleRangeSearch_CustomPrecisionStep() throws Exception {
    for (long i = Integer.MAX_VALUE, c = 0; i < (long) Integer.MAX_VALUE + 10l; i++) {
      assertU(adoc("id", String.valueOf(c++), "tdouble4", String.valueOf(i * 2.33d)));
    }
    assertU(commit());
    String fq = "tdouble4:[" + Integer.MAX_VALUE * 2.33d + " TO " + (5l + Integer.MAX_VALUE) * 2.33d + "]";
    assertQ("Range filter must match only 5 documents", req("q", "*:*", "fq", fq), "//*[@numFound='6']");
  }
}
