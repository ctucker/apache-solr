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

package org.apache.solr.analysis;

import java.io.IOException;
import java.io.StringReader;

import junit.framework.TestCase;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

/**
 * General token testing helper functions
 */
public abstract class BaseTokenTestCase extends TestCase
{
  // some helpers to test Analyzers and TokenStreams:
  // these are taken from Lucene's BaseTokenStreamTestCase
   
  public static void assertTokenStreamContents(TokenStream ts, String[] output,
      int startOffsets[], int endOffsets[], String types[], int posIncrements[])
      throws IOException {
    assertNotNull(output);
    assertTrue("has TermAttribute", ts.hasAttribute(TermAttribute.class));
    TermAttribute termAtt = (TermAttribute) ts
        .getAttribute(TermAttribute.class);
    
    OffsetAttribute offsetAtt = null;
    if (startOffsets != null || endOffsets != null) {
      assertTrue("has OffsetAttribute", ts.hasAttribute(OffsetAttribute.class));
      offsetAtt = (OffsetAttribute) ts.getAttribute(OffsetAttribute.class);
    }
    
    TypeAttribute typeAtt = null;
    if (types != null) {
      assertTrue("has TypeAttribute", ts.hasAttribute(TypeAttribute.class));
      typeAtt = (TypeAttribute) ts.getAttribute(TypeAttribute.class);
    }
    
    PositionIncrementAttribute posIncrAtt = null;
    if (posIncrements != null) {
      assertTrue("has PositionIncrementAttribute", ts
          .hasAttribute(PositionIncrementAttribute.class));
      posIncrAtt = (PositionIncrementAttribute) ts
          .getAttribute(PositionIncrementAttribute.class);
    }
    
    ts.reset();
    for (int i = 0; i < output.length; i++) {
      // extra safety to enforce, that the state is not preserved and also
      // assign bogus values
      ts.clearAttributes();
      termAtt.setTermBuffer("bogusTerm");
      if (offsetAtt != null) offsetAtt.setOffset(14584724, 24683243);
      if (typeAtt != null) typeAtt.setType("bogusType");
      if (posIncrAtt != null) posIncrAtt.setPositionIncrement(45987657);
      
      assertTrue("token " + i + " exists", ts.incrementToken());
      assertEquals("term " + i, output[i], termAtt.term());
      if (startOffsets != null) assertEquals("startOffset " + i,
          startOffsets[i], offsetAtt.startOffset());
      if (endOffsets != null) assertEquals("endOffset " + i, endOffsets[i],
          offsetAtt.endOffset());
      if (types != null) assertEquals("type " + i, types[i], typeAtt.type());
      if (posIncrements != null) assertEquals("posIncrement " + i,
          posIncrements[i], posIncrAtt.getPositionIncrement());
    }
    assertFalse("end of stream", ts.incrementToken());
    ts.end();
    ts.close();
  }
  
  public static void assertTokenStreamContents(TokenStream ts, String[] output)
      throws IOException {
    assertTokenStreamContents(ts, output, null, null, null, null);
  }
  
  public static void assertTokenStreamContents(TokenStream ts, String[] output,
      String[] types) throws IOException {
    assertTokenStreamContents(ts, output, null, null, types, null);
  }
  
  public static void assertTokenStreamContents(TokenStream ts, String[] output,
      int[] posIncrements) throws IOException {
    assertTokenStreamContents(ts, output, null, null, null, posIncrements);
  }
  
  public static void assertTokenStreamContents(TokenStream ts, String[] output,
      int startOffsets[], int endOffsets[]) throws IOException {
    assertTokenStreamContents(ts, output, startOffsets, endOffsets, null, null);
  }
  
  public static void assertTokenStreamContents(TokenStream ts, String[] output,
      int startOffsets[], int endOffsets[], int[] posIncrements)
      throws IOException {
    assertTokenStreamContents(ts, output, startOffsets, endOffsets, null,
        posIncrements);
  }
  
  public static void assertAnalyzesTo(Analyzer a, String input,
      String[] output, int startOffsets[], int endOffsets[], String types[],
      int posIncrements[]) throws IOException {
    assertTokenStreamContents(a.tokenStream("dummy", new StringReader(input)),
        output, startOffsets, endOffsets, types, posIncrements);
  }
  
  public static void assertAnalyzesTo(Analyzer a, String input, String[] output)
      throws IOException {
    assertAnalyzesTo(a, input, output, null, null, null, null);
  }
  
  public static void assertAnalyzesTo(Analyzer a, String input,
      String[] output, String[] types) throws IOException {
    assertAnalyzesTo(a, input, output, null, null, types, null);
  }
  
  public static void assertAnalyzesTo(Analyzer a, String input,
      String[] output, int[] posIncrements) throws IOException {
    assertAnalyzesTo(a, input, output, null, null, null, posIncrements);
  }
  
  public static void assertAnalyzesTo(Analyzer a, String input,
      String[] output, int startOffsets[], int endOffsets[]) throws IOException {
    assertAnalyzesTo(a, input, output, startOffsets, endOffsets, null, null);
  }
  
  public static void assertAnalyzesTo(Analyzer a, String input,
      String[] output, int startOffsets[], int endOffsets[], int[] posIncrements)
      throws IOException {
    assertAnalyzesTo(a, input, output, startOffsets, endOffsets, null,
        posIncrements);
  }
}
