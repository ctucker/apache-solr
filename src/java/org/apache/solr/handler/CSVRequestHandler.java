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

package org.apache.solr.handler;

import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrParams;
import org.apache.solr.request.SolrQueryResponse;
import org.apache.solr.util.ContentStream;
import org.apache.solr.core.SolrException;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.util.StrUtils;
import org.apache.solr.update.*;
import org.apache.commons.csv.CSVStrategy;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.io.IOUtils;

import java.util.regex.Pattern;
import java.util.List;
import java.io.*;

/**
 * @author yonik
 * @version $Id$
 */

public class CSVRequestHandler extends RequestHandlerBase {

  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    CSVLoader loader = new SingleThreadedCSVLoader(req);

    Iterable<ContentStream> streams = req.getContentStreams();
    if (streams == null) {
      throw new SolrException(400, "missing content stream");
    }

    for(ContentStream stream : streams) {
      Reader reader = stream.getReader();
      try {
        loader.errHeader = "CSVLoader: input=" + stream.getSourceInfo(); 
        loader.load(reader);
      } finally {
        IOUtils.closeQuietly(reader);
      }
    }
  }

  //////////////////////// SolrInfoMBeans methods //////////////////////
  @Override
  public String getDescription() {
    return "Add/Update multiple documents with CSV formatted rows";
  }

  @Override
  public String getVersion() {
      return "$Revision:$";
 }

 @Override
 public String getSourceId() {
    return "$Id:$";
  }

  @Override
  public String getSource() {
    return "$URL:$";
  }
}


abstract class CSVLoader {
  static String SEPARATOR="separator";
  static String FIELDNAMES="fieldnames";
  static String HEADER="header";
  static String SKIP="skip";
  static String MAP="map";
  static String TRIM="trim";
  static String EMPTY="keepEmpty";
  static String SPLIT="split";
  static String ENCAPSULATOR="encapsulator";
  static String COMMIT="commit";
  static String OVERWRITE="overwrite";

  private static Pattern colonSplit = Pattern.compile(":");
  private static Pattern commaSplit = Pattern.compile(",");

  final IndexSchema schema;
  final SolrParams params;
  final UpdateHandler handler;
  final CSVStrategy strategy;

  String[] fieldnames;
  SchemaField[] fields;
  CSVLoader.FieldAdder[] adders;

  int skipLines;    // number of lines to skip at start of file

  final AddUpdateCommand templateAdd;


  /** Add a field to a document unless it's zero length.
   * The FieldAdder hierarchy handles all the complexity of
   * further transforming or splitting field values to keep the
   * main logic loop clean.  All implementations of add() must be
   * MT-safe!
   */
  private class FieldAdder {
    void add(DocumentBuilder builder, int line, int column, String val) {
      if (val.length() > 0) {
        builder.addField(fields[column].getName(),val,1.0f);
      }
    }
  }

  /** add zero length fields */
  private class FieldAdderEmpty extends CSVLoader.FieldAdder {
    void add(DocumentBuilder builder, int line, int column, String val) {
      builder.addField(fields[column].getName(),val,1.0f);
    }
  }

  /** trim fields */
  private class FieldTrimmer extends CSVLoader.FieldAdder {
    private final CSVLoader.FieldAdder base;
    FieldTrimmer(CSVLoader.FieldAdder base) { this.base=base; }
    void add(DocumentBuilder builder, int line, int column, String val) {
      base.add(builder, line, column, val.trim());
    }
  }

  /** map a single value.
   * for just a couple of mappings, this is probably faster than
   * using a HashMap.
   */
 private class FieldMapperSingle extends CSVLoader.FieldAdder {
   private final String from;
   private final String to;
   private final CSVLoader.FieldAdder base;
   FieldMapperSingle(String from, String to, CSVLoader.FieldAdder base) {
     this.from=from;
     this.to=to;
     this.base=base;
   }
    void add(DocumentBuilder builder, int line, int column, String val) {
      if (from.equals(val)) val=to;
      base.add(builder,line,column,val);
    }
 }

  /** Split a single value into multiple values based on
   * a CSVStrategy.
   */
  private class FieldSplitter extends CSVLoader.FieldAdder {
    private final CSVStrategy strategy;
    private final CSVLoader.FieldAdder base;
    FieldSplitter(CSVStrategy strategy, CSVLoader.FieldAdder base) {
      this.strategy = strategy;
      this.base = base;
    }

    void add(DocumentBuilder builder, int line, int column, String val) {
      CSVParser parser = new CSVParser(new StringReader(val), strategy);
      try {
        String[] vals = parser.getLine();
        if (vals!=null) {
          for (String v: vals) base.add(builder,line,column,v);
        } else {
          base.add(builder,line,column,val);
        }
      } catch (IOException e) {
        throw new SolrException(400,"");
      }
    }
  }


  String errHeader="CSVLoader:";

  CSVLoader(SolrQueryRequest req) {
    this.params = req.getParams();
    handler = req.getCore().getUpdateHandler();
    schema = req.getSchema();

    templateAdd = new AddUpdateCommand();
    templateAdd.allowDups=false;
    templateAdd.overwriteCommitted=true;
    templateAdd.overwritePending=true;

    if (params.getBool(OVERWRITE,true)) {
      templateAdd.allowDups=false;
      templateAdd.overwriteCommitted=true;
      templateAdd.overwritePending=true;
    } else {
      templateAdd.allowDups=true;
      templateAdd.overwriteCommitted=false;
      templateAdd.overwritePending=false;
    }

    strategy = new CSVStrategy(',', '"', CSVStrategy.COMMENTS_DISABLED, true,  false, true);
    String sep = params.get(SEPARATOR);
    if (sep!=null) {
      if (sep.length()!=1) throw new SolrException(400,"Invalid separator:'"+sep+"'");
      strategy.setDelimiter(sep.charAt(0));
    }

    String encapsulator = params.get(ENCAPSULATOR);
    if (encapsulator!=null) {
      if (encapsulator.length()!=1) throw new SolrException(400,"Invalid encapsulator:'"+sep+"'");
      strategy.setEncapsulator(encapsulator.charAt(0));
    }

    String fn = params.get(FIELDNAMES);
    fieldnames = fn != null ? commaSplit.split(fn,-1) : null;

    Boolean hasHeader = params.getBool(HEADER);

    skipLines = params.getInt(SKIP,0);

    if (fieldnames==null) {
      if (null == hasHeader) {
        // assume the file has the headers if they aren't supplied in the args
        hasHeader=true;
      } else if (hasHeader) {
        throw new SolrException(400,"CSVLoader: must specify fieldnames=<fields>* or header=true");
      }
    } else {
      // if the fieldnames were supplied and the file has a header, we need to
      // skip over that header.
      if (hasHeader!=null && hasHeader) skipLines++;

      prepareFields();
    }
  }

  /** create the FieldAdders that control how each field  is indexed */
  void prepareFields() {
    // Possible future optimization: for really rapid incremental indexing
    // from a POST, one could cache all of this setup info based on the params.
    // The link from FieldAdder to this would need to be severed for that to happen.

    fields = new SchemaField[fieldnames.length];
    adders = new CSVLoader.FieldAdder[fieldnames.length];
    String skipStr = params.get(SKIP);
    List<String> skipFields = skipStr==null ? null : StrUtils.splitSmart(skipStr,',');

    CSVLoader.FieldAdder adder = new CSVLoader.FieldAdder();
    CSVLoader.FieldAdder adderKeepEmpty = new CSVLoader.FieldAdderEmpty();

    for (int i=0; i<fields.length; i++) {
      String fname = fieldnames[i];
      // to skip a field, leave the entries in fields and addrs null
      if (fname.length()==0 || (skipFields!=null && skipFields.contains(fname))) continue;

      fields[i] = schema.getField(fname);
      boolean keepEmpty = params.getFieldBool(fname,EMPTY,false);
      adders[i] = keepEmpty ? adderKeepEmpty : adder;

      // Order that operations are applied: split -> trim -> map -> add
      // so create in reverse order.
      // Creation of FieldAdders could be optimized and shared among fields

      String[] fmap = params.getFieldParams(fname,MAP);
      if (fmap!=null) {
        for (String mapRule : fmap) {
          String[] mapArgs = colonSplit.split(mapRule,-1);
          if (mapArgs.length!=2)
            throw new SolrException(400, "Map rules must be of the form 'from:to' ,got '"+mapRule+"'");
          adders[i] = new CSVLoader.FieldMapperSingle(mapArgs[0], mapArgs[1], adders[i]);
        }
      }

      if (params.getFieldBool(fname,TRIM,false)) {
        adders[i] = new CSVLoader.FieldTrimmer(adders[i]);
      }

      if (params.getFieldBool(fname,SPLIT,false)) {
        String sepStr = params.getFieldParam(fname,SEPARATOR);
        char fsep = sepStr==null || sepStr.length()==0 ? ',' : sepStr.charAt(0);
        String encStr = params.getFieldParam(fname,ENCAPSULATOR);
        char fenc = encStr==null || encStr.length()==0 ? '\'' : encStr.charAt(0);

        CSVStrategy fstrat = new CSVStrategy(fsep,fenc,CSVStrategy.COMMENTS_DISABLED);
        adders[i] = new CSVLoader.FieldSplitter(fstrat, adders[i]);
      }
    }
  }

  private void input_err(String msg, String[] line, int lineno) {
    StringBuilder sb = new StringBuilder();
    sb.append(errHeader+", line="+lineno + ","+msg+"\n\tvalues={");
    for (String val: line) { sb.append("'"+val+"',"); }
    sb.append('}');
    throw new SolrException(400,sb.toString());
  }

  /** load the CSV input */
  void load(Reader input) throws IOException {
    Reader reader = input;
    if (skipLines>0) {
      if (!(reader instanceof BufferedReader)) {
        reader = new BufferedReader(reader);
      }
      BufferedReader r = (BufferedReader)reader;
      for (int i=0; i<skipLines; i++) {
        r.readLine();
      }
    }

    CSVParser parser = new CSVParser(reader, strategy);

    // parse the fieldnames from the header of the file
    if (fieldnames==null) {
      fieldnames = parser.getLine();
      if (fieldnames==null) {
        throw new SolrException(400,"Expected fieldnames in CSV input");
      }
      prepareFields();
    }

    // read the rest of the CSV file
    for(;;) {
      int line = parser.getLineNumber();  // for error reporting in MT mode
      String[] vals = parser.getLine();
      if (vals==null) break;

      if (vals.length != fields.length) {
        input_err("expected "+fields.length+" values but got "+vals.length, vals, line);
      }

      addDoc(line,vals);
    }

    if (params.getBool(COMMIT,true)) {
      handler.commit(new CommitUpdateCommand(false));
    }
  }

  /** called for each line of values (document) */
  abstract void addDoc(int line, String[] vals) throws IOException;

  /** this must be MT safe... may be called concurrently from multiple threads. */
  void doAdd(int line, String[] vals, DocumentBuilder builder, AddUpdateCommand template) throws IOException {
    // the line number is passed simply for error reporting in MT mode.
    // first, create the lucene document
    builder.startDoc();
    for (int i=0; i<vals.length; i++) {
      if (fields[i]==null) continue;  // ignore this field
      String val = vals[i];
      adders[i].add(builder, line, i, val);
    }
    builder.endDoc();

    template.doc = builder.getDoc();
    handler.addDoc(template);
  }

}


class SingleThreadedCSVLoader extends CSVLoader {
  protected DocumentBuilder builder;

  SingleThreadedCSVLoader(SolrQueryRequest req) {
    super(req);
    builder = new DocumentBuilder(schema);
  }

  void addDoc(int line, String[] vals) throws IOException {
    templateAdd.indexedId = null;
    doAdd(line, vals, builder, templateAdd);
  }
}

