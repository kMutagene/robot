package org.obolibrary.robot;

import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.interpret.RenderResult;
import com.hubspot.jinjava.interpret.TemplateError;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxClassExpressionParser;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.util.AnnotationValueShortFormProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements validation on a particular table.
 *
 * @author <a href="mailto:consulting@michaelcuffaro.com">Michael E. Cuffaro</a>
 */
public class TableValidator {
  /** Logger */
  private static final Logger logger = LoggerFactory.getLogger(TableValidator.class);

  /** Namespace for error messages. */
  private static final String NS = "validate#";

  /** Error message for a rule that couldn't be parsed */
  private static final String malformedRuleError = NS + "MALFORMED RULE ERROR malformed rule: %s";

  /**
   * Error message for an invalid presence rule. Presence rules must be in the form of a truth
   * value.
   */
  private static final String invalidPresenceRuleError =
      NS
          + "INVALID PRESENCE RULE ERROR in column %d: invalid rule: \"%s\" for rule type: %s. Must be "
          + "one of: true, t, 1, yes, y, false, f, 0, no, n";

  /**
   * Error reported when a wildcard in a rule specifies a column greater than the number of columns
   * in the table.
   */
  private static final String columnOutOfRangeError =
      NS
          + "COLUMN OUT OF RANGE ERROR in column %d: rule \"%s\" indicates a column number that is "
          + "greater than the row length (%d).";

  /** Error reported when a when-clause does not have a corresponding main clause */
  private static final String noMainError =
      NS + "NO MAIN ERROR in column %d: rule: \"%s\" has when clause but no main clause.";

  /** Error reported when a when-clause can't be parsed */
  private static final String malformedWhenClauseError =
      NS + "MALFORMED WHEN CLAUSE ERROR in column %d: unable to decompose when-clause: \"%s\".";

  /** Error reported when a when-clause is of an invalid or inappropriate type */
  private static final String invalidWhenTypeError =
      NS
          + "INVALID WHEN TYPE ERROR in column %d: in clause: \"%s\": Only rules of type: %s are "
          + "allowed in a when clause.";

  /** Error reported when a query type is unrecognized */
  private static final String unrecognizedQueryTypeError =
      NS
          + "UNRECOGNIZED QUERY TYPE ERROR in column %d: query type \"%s\" not recognized in rule "
          + "\"%s\".";

  /** Error reported when a rule type is not recognized */
  private static final String unrecognizedRuleTypeError =
      NS + "UNRECOGNIZED RULE TYPE ERROR in column %d: unrecognized rule type \"%s\".";

  /**
   * An enum representation of the different categories of rules. We distinguish between queries,
   * which involve queries to a reasoner, and presence rules, which check for the existence of
   * content in a cell.
   */
  private enum RCatEnum {
    QUERY,
    PRESENCE
  }

  /**
   * An enum representation of the different types of rules. Each rule type belongs to larger
   * category, and is identified within the CSV file by a particular string.
   */
  private enum RTypeEnum {
    DIRECT_SUPER("direct-superclass-of", RCatEnum.QUERY),
    SUPER("superclass-of", RCatEnum.QUERY),
    EQUIV("equivalent-to", RCatEnum.QUERY),
    DIRECT_SUB("direct-subclass-of", RCatEnum.QUERY),
    SUB("subclass-of", RCatEnum.QUERY),
    DIRECT_INSTANCE("direct-instance-of", RCatEnum.QUERY),
    INSTANCE("instance-of", RCatEnum.QUERY),
    REQUIRED("is-required", RCatEnum.PRESENCE),
    EXCLUDED("is-excluded", RCatEnum.PRESENCE);

    private final String ruleType;
    private final RCatEnum ruleCat;

    RTypeEnum(String ruleType, RCatEnum ruleCat) {
      this.ruleType = ruleType;
      this.ruleCat = ruleCat;
    }

    private String getRuleType() {
      return ruleType;
    }

    private RCatEnum getRuleCat() {
      return ruleCat;
    }
  }

  /** Reverse map from rule types (as Strings) to RTypeEnums, populated at load time */
  private static final Map<String, RTypeEnum> rule_type_to_rtenum_map = new HashMap<>();

  static {
    for (RTypeEnum r : RTypeEnum.values()) {
      rule_type_to_rtenum_map.put(r.getRuleType(), r);
    }
  }

  /**
   * Reverse map from rule types in the QUERY category (as Strings) to RTypeEnums, populated at load
   * time
   */
  private static final Map<String, RTypeEnum> query_type_to_rtenum_map = new HashMap<>();

  static {
    for (RTypeEnum r : RTypeEnum.values()) {
      if (r.getRuleCat() == RCatEnum.QUERY) {
        query_type_to_rtenum_map.put(r.getRuleType(), r);
      }
    }
  }

  /** The ontology that forms the basis of the validation */
  private OWLOntology ontology;

  /** The parser to use when validating class expressions */
  private ManchesterOWLSyntaxClassExpressionParser parser;

  /** The reasoner to use when executing queries */
  private OWLReasoner reasoner;

  /** The data factory for generating data based on the ontology during validation */
  private OWLDataFactory dataFactory;

  /** A convenience map from IRIs to labels */
  private Map<IRI, String> iri_to_label_map;

  /** A convenience map from labels to IRIs */
  private Map<String, IRI> label_to_iri_map;

  /** The output writer; if null or not defined, output will go to STDOUT */
  private Writer writer;

  /** The output stream to use for XLSX output */
  private OutputStream xlsxFileOutputStream;

  /** The workbook within which the XLSX validation report will be saved */
  private Workbook workbook;

  /** The jinja context to save the HTML validation report to */
  private Map<String, Object> jinjaTblContext;

  /** The current row being validated */
  private int tbl_row_index;

  /** The current column being validated */
  private int tbl_col_index;

  /** The number of non-data rows in the XLSX output */
  private int xlsx_non_data_row_index;

  /** Enum used with the writelog() method */
  private enum LogLevel {
    DEBUG,
    ERROR,
    INFO,
    WARN;
  }

  /**
   * Given the string `format`, a logging level specification, and a number of formatting variables,
   * use the formating variables to fill in the format string in the manner of C's printf function,
   * and write to the log at the appropriate log level. If the parameter `showCoords` is true, then
   * include the current row and column number in the output string.
   */
  private void writelog(
      boolean showCoords, LogLevel logLevel, String format, Object... positionalArgs) {
    String logStr = "";
    if (showCoords) {
      logStr += String.format("At row: %d, column: %d: ", tbl_row_index + 1, tbl_col_index + 1);
    }

    logStr += String.format(format, positionalArgs);
    switch (logLevel) {
      case ERROR:
        logger.error(logStr);
        break;
      case WARN:
        logger.warn(logStr);
        break;
      case INFO:
        logger.info(logStr);
        break;
      case DEBUG:
        logger.debug(logStr);
        break;
    }
  }

  /**
   * Given the string `format`, a logging level specification, and a number of formatting variables,
   * use the formating variables to fill in the format string in the manner of C's printf function,
   * and write to the log at the appropriate log level, including the current row and column number
   * in the output string.
   */
  private void writelog(LogLevel logLevel, String format, Object... positionalArgs) {
    writelog(true, logLevel, format, positionalArgs);
  }

  /**
   * Constructor. Initialise the table validator instance based on the given parameters, and set the
   * number of XLSX non-data rows to 0.
   */
  TableValidator(
      OWLOntology ontology,
      ManchesterOWLSyntaxClassExpressionParser parser,
      OWLReasoner reasoner,
      OWLDataFactory dataFactory,
      Map<IRI, String> iri_to_label_map,
      Map<String, IRI> label_to_iri_map) {

    this.ontology = ontology;
    this.parser = parser;
    this.reasoner = reasoner;
    this.dataFactory = dataFactory;
    this.iri_to_label_map = iri_to_label_map;
    this.label_to_iri_map = label_to_iri_map;
    this.xlsx_non_data_row_index = 0;
  }

  /** Flushes any remaining output in the XLSX output stream and/or the writer and closes them */
  private void tearDown() throws IOException {
    if (xlsxFileOutputStream != null) {
      workbook.write(xlsxFileOutputStream);
      xlsxFileOutputStream.close();
    }

    if (writer != null) {
      writer.flush();
      writer.close();
    }
  }

  /** Given lists of strings representing non-data rows, add them to the XLSX workbook. */
  private void add_non_data_rows_to_xlsx(List<List<String>> rowsToAdd) throws Exception {
    for (List<String> rowToAdd : rowsToAdd) {
      Sheet worksheet = workbook.getSheetAt(0);
      Row row = worksheet.createRow(xlsx_non_data_row_index++);
      for (int i = 0; i < rowToAdd.size(); i++) {
        Cell cell = row.createCell(i);
        cell.setCellValue(rowToAdd.get(i));
      }
    }
  }

  /**
   * Given a string describing the contents of a cell in the input CSV, add those contents to the
   * corresponding cell in the XLSX workbook.
   */
  private void write_xlsx(String cellString) throws IOException {
    Sheet worksheet = workbook.getSheetAt(0);
    Row row = worksheet.getRow(xlsx_non_data_row_index + tbl_row_index);
    // Create a new row if the one we need to add to doesn't already exist in the XLSX workbook:
    if (row == null) {
      row = worksheet.createRow(xlsx_non_data_row_index + tbl_row_index);
    }

    Cell cell = row.getCell(tbl_col_index);
    // Create a new cell if the one we need to add to doesn't already exist in the row:
    if (cell == null) {
      cell = row.createCell(tbl_col_index);
    }

    cell.setCellValue(cellString);
  }

  /**
   * Given a string describing the contents of a cell in the input CSV, and a flag indicating
   * whether to write the contents to the output file verbatim: (1) If the verbatim flag is set to
   * true, convert any named objects indicated in that cell to hyperlinks; (2) either way, add the
   * content to the corresponding cell in the jinja context for the HTML output.
   */
  private void write_html(String cellString, boolean verbatim) throws Exception {
    // Extract all the data entries contained within the current cell; for example we might have
    // "entry1 | entry2 | entry3" as the contents of a cell. We will treat each of these as separate
    // data entries and process them individually.
    String[] cellData = split_on_pipes(cellString.trim());
    String label = null;
    OWLClassExpression ce = null;
    String htmlCellString = "";

    if (verbatim) {
      htmlCellString = cellString;
    } else {
      for (int i = 0; i < cellData.length; i++) {
        if (!cellData[i].trim().equals("")) {
          // If the content of the given data entry is a label, then use the label_to_iri_map to
          // get its corresponding IRI, and format the data entry as a HTML link with the IRI as its
          // href:
          if ((label = get_label_from_term(cellData[i])) != null) {
            IRI iri = label_to_iri_map.get(label);
            htmlCellString +=
                String.format("<a href=\"%s\" target=\"__blank\">%s</a>", iri.toString(), label);
          }
          // If the content of the given data entry is a general class expression, then use the
          // ManchesterOWLSyntaxClassExpressionHTMLRenderer to render it as a complex of
          // sub-expressions in the form of HTML links, unless the class expression happens to be an
          // IRI or a literal. If it is an IRI then we don't want to generate a hyperlink since we
          // do not want to have self-referential links. If it is a literal then it is either a
          // comment or a typo (in the form of a valid class expression), since all literal labels
          // should be captured by the if/else branch above. If it is an IRI or a literal, then
          // just write it as is without rendering it.
          else if ((ce = get_class_expression_from_string(cellData[i], LogLevel.DEBUG)) != null
              && !ce.isIRI()
              && !ce.isClassExpressionLiteral()) {
            StringWriter strWriter = new StringWriter();
            // As a result of the call below the rendered contents will be in strWriter:
            new ManchesterOWLSyntaxObjectHTMLRenderer(
                    strWriter,
                    new AnnotationValueShortFormProvider(
                        Collections.singletonList(dataFactory.getRDFSLabel()),
                        Collections.emptyMap(),
                        ontology.getOWLOntologyManager()))
                .visit(ce);
            htmlCellString += (strWriter != null ? strWriter : cellData[i]);
          }
          // If the content of the given data entry is neither a label nor a non-literal class
          // expression, then just write it as is:
          else {
            htmlCellString += cellData[i];
          }
        }

        // If this is not the last data entry, add a pipe symbol to demarcate it from the next data
        // entry:
        if ((i + 1) < cellData.length) {
          htmlCellString += " | ";
        }
      }
    }

    // Add the generated HTML for the current cell to the dataContext:
    List<List<Map<String, String>>> dataContext =
        (List<List<Map<String, String>>>) jinjaTblContext.get("dataRows");
    dataContext.get(tbl_row_index).get(tbl_col_index).put("content", htmlCellString);
  }

  /**
   * Given the string `format` and a number of formatting variables, use the formatting variables to
   * fill in the format string in the manner of C's printf function, add a comment to XLSX workbook
   * with that string at the current location, and highlight the cell at that location in red.
   */
  private void report_xlsx(String format, Object... positionalArgs) throws IOException {
    Sheet worksheet = workbook.getSheetAt(0);
    Row row = worksheet.getRow(xlsx_non_data_row_index + tbl_row_index);
    if (row == null) {
      writelog(
          LogLevel.ERROR,
          "Row %d does not exist in worksheet.",
          xlsx_non_data_row_index + tbl_row_index);
      return;
    }

    Cell cell = row.getCell(tbl_col_index);
    if (cell == null) {
      writelog(
          LogLevel.ERROR,
          "Cell %d of row %d does not exist in worksheet.",
          tbl_col_index,
          xlsx_non_data_row_index + tbl_row_index);
      return;
    }

    // Set the style of the current cell to a red background with a white font:
    CellStyle style = workbook.createCellStyle();
    style.setFillBackgroundColor(IndexedColors.RED.getIndex());
    style.setFillPattern(FillPatternType.FINE_DOTS);
    Font font = workbook.createFont();
    font.setColor(IndexedColors.WHITE.getIndex());
    style.setFont(font);
    cell.setCellStyle(style);

    // Attach a Comment object to the cell. If one for this cell already exists, extract the old
    // comment string from it and prepend it to the new comment string, then remove the old Comment
    // object and create a new one for the combined comment string and attach it to the cell.
    String commentString = String.format(format, positionalArgs);
    Comment comment = cell.getCellComment();
    if (comment != null) {
      commentString = comment.getString().getString() + "; " + commentString;
      cell.removeCellComment();
    }

    // When the comment box is visible, have it show in a 1x10 space
    CreationHelper factory = workbook.getCreationHelper();
    Drawing drawing = worksheet.createDrawingPatriarch();
    ClientAnchor anchor = factory.createClientAnchor();
    anchor.setCol1(cell.getColumnIndex());
    anchor.setCol2(cell.getColumnIndex() + 1);
    anchor.setRow1(row.getRowNum());
    anchor.setRow2(row.getRowNum() + 10);

    comment = drawing.createCellComment(anchor);
    RichTextString str = factory.createRichTextString(commentString);
    comment.setString(str);
    comment.setAuthor("Robot Validate Operation");
    // Assign the comment to the cell
    cell.setCellComment(comment);
  }

  /**
   * Given two lists of strings representing the header and rules rows, respectively, and an integer
   * representing the number of data rows that are going to be validated, add the header and rules
   * rows to the jinja context, and add another subcontext for the data rows, with entries for every
   * cell in every row of the data. These will later be written to with individual validation
   * results.
   */
  private void prepare_jinja_context(
      List<String> headerRow, List<String> rulesRow, int numberOfDataRows) throws Exception {

    // Add the header and rules rows to the jinja context
    jinjaTblContext.put("headerRow", headerRow);
    jinjaTblContext.put("rulesRow", rulesRow);

    // Add another subcontext to the jinja context which will hold the information for the data rows
    // of the output HTML. Here we only create enough memory to hold every cell of every row. The
    // contents of the cells will be filled in with specific validation information later.
    List<List> dataContext = new ArrayList();
    for (int i = 0; i < numberOfDataRows; i++) {
      List<Map> rowContext = new ArrayList();
      for (int j = 0; j < headerRow.size(); j++) {
        rowContext.add(new HashMap());
      }
      dataContext.add(rowContext);
    }
    jinjaTblContext.put("dataRows", dataContext);
  }

  /**
   * Given the string `format` and a number of formatting variables, use the formatting variables to
   * fill in the format string in the manner of C's printf function, add a comment to jinja context
   * with that string at the current location.
   */
  private void report_jinja_context(String format, Object... positionalArgs) {
    String commentString = String.format(format, positionalArgs);
    List<List<Map<String, String>>> dataContext =
        (List<List<Map<String, String>>>) jinjaTblContext.get("dataRows");
    Map<String, String> cellContext = dataContext.get(tbl_row_index).get(tbl_col_index);
    String oldComment = cellContext.get("comment");
    if (oldComment == null) {
      cellContext.put("comment", commentString);
    } else {
      cellContext.put("comment", oldComment + "; " + commentString);
    }
  }

  /**
   * Render a HTML report based on the data validated, using jinja2 templates and the current jinja
   * context.
   */
  private void generate_html_report(boolean standalone) throws IOException {
    String template =
        IOUtils.toString(
            ValidateOperation.class.getResourceAsStream("/validate-table-template.jinja2"),
            StandardCharsets.UTF_8);

    RenderResult result = new Jinjava().renderForResult(template, jinjaTblContext);
    if (result.hasErrors()) {
      for (TemplateError error : result.getErrors()) {
        writelog(
            false,
            LogLevel.ERROR,
            "Jinjava error at line %s (severity %s): %s.",
            error.getLineno(),
            error.getSeverity(),
            error.getMessage());
      }
    }

    String tblOutput = result.getOutput();
    if (!standalone) {
      // If this isn't a standalone report then we are done. Just write the generated table code
      // using the writer:
      writer.write(tblOutput);
    } else {
      // If this is a standalone report, then we need to wrap the generated table in the code
      // that is given in the wrapper template. For this we create a new context which will
      // contain a single entry for the generated table, and which will be passed to Jinjava.
      HashMap<String, String> jinjaHtmlContext = new HashMap();
      jinjaHtmlContext.put("table", tblOutput);

      template =
          IOUtils.toString(
              ValidateOperation.class.getResourceAsStream("/validate-template.jinja2"),
              StandardCharsets.UTF_8);

      result = new Jinjava().renderForResult(template, jinjaHtmlContext);
      if (result.hasErrors()) {
        for (TemplateError error : result.getErrors()) {
          writelog(
              false,
              LogLevel.ERROR,
              "Jinjava error at line %s (severity %s): %s.",
              error.getLineno(),
              error.getSeverity(),
              error.getMessage());
        }
      }

      writer.write(result.getOutput());
    }
  }

  /**
   * Given the string `format` and a number of formatting variables, use the formatting variables to
   * fill in the format string in the manner of C's printf function, and write the string to the
   * Writer object (or XLSX workbook, or Jinja context) that belongs to ValidateOperation. If the
   * parameter `showCoords` is true, then include the current row and column number in the output
   * string.
   */
  private void report(boolean showCoords, String format, Object... positionalArgs)
      throws IOException {

    if (workbook != null && showCoords) {
      report_xlsx(format, positionalArgs);
    } else if (jinjaTblContext != null && showCoords) {
      report_jinja_context(format, positionalArgs);
    } else {
      String outStr = "";
      if (showCoords) {
        outStr += String.format("At row: %d, column: %d: ", tbl_row_index + 1, tbl_col_index + 1);
      }
      outStr += String.format(format, positionalArgs);
      if (writer != null) {
        writer.write(outStr + "\n");
      } else {
        System.out.print(outStr + "\n");
      }
    }
  }

  /**
   * Given the string `format` and a number of formatting variables, use the formatting variables to
   * fill in the format string in the manner of C's printf function, and write the string to the
   * Writer object that belongs to ValidateOperation, including the current row and column number in
   * the output string.
   */
  private void report(String format, Object... positionalArgs) throws IOException {
    report(true, format, positionalArgs);
  }

  /**
   * Given a string specifying a list of rules of various types, return a map which contains, for
   * each rule type present in the string, the list of rules of that type that have been specified.
   */
  private Map<String, List<String>> parse_rules(String ruleString) throws Exception {
    HashMap<String, List<String>> ruleMap = new HashMap();
    // Skip over empty strings and strings that start with "##".
    if (!ruleString.trim().equals("") && !ruleString.trim().startsWith("##")) {
      // Rules are separated by semicolons:
      String[] rules = ruleString.split("\\s*;\\s*");
      for (String rule : rules) {
        // Skip any rules that begin with a '#' (these are interpreted as commented out):
        if (rule.trim().startsWith("#")) {
          continue;
        }
        // Each rule is of the form: <rule-type> <rule-content> but for the PRESENCE category, if
        // <rule-content> is left out it is implicitly understood to be "true"
        String[] ruleParts = rule.trim().split("\\s+", 2);
        String ruleType = ruleParts[0].trim();
        String ruleContent = null;
        if (ruleParts.length == 2) {
          ruleContent = ruleParts[1].trim();
        } else {
          RTypeEnum rTypeEnum = rule_type_to_rtenum_map.get(ruleType);
          if (rTypeEnum != null && rTypeEnum.getRuleCat() == RCatEnum.PRESENCE) {
            ruleContent = "true";
          } else {
            throw new Exception(String.format(malformedRuleError, rule.trim()));
          }
        }

        // Add, to the map, a new empty list for the given ruleType if we haven't seen it before:
        if (!ruleMap.containsKey(ruleType)) {
          ruleMap.put(ruleType, new ArrayList<String>());
        }
        // Add the content of the given rule to the list of rules corresponding to its ruleType:
        ruleMap.get(ruleType).add(ruleContent);
      }
    }
    return ruleMap;
  }

  /**
   * Given a list of strings representing a row from the table, return true if any of the cells in
   * the row has non-whitespace content.
   */
  private boolean has_content(List<String> row) {
    for (String cell : row) {
      if (!cell.trim().equals("")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Given a string of substrings split by pipes ('|'), return an array with the first substring in
   * the 0th position of the array, the second substring in the 1st position, and so on.
   */
  private String[] split_on_pipes(String ruleType) {
    // A rule type can be of the form: ruletype1|ruletype2|ruletype3...
    // where the first one is the primary type for lookup purposes:
    return ruleType.split("\\s*\\|\\s*");
  }

  /**
   * Given a string describing a compound rule type, return the primary rule type of the compound
   * rule type.
   */
  private String get_primary_rule_type(String ruleType) {
    return split_on_pipes(ruleType)[0];
  }

  /**
   * Given a string describing a rule type, return a boolean indicating whether it is one of the
   * rules recognized by ValidateOperation.
   */
  private boolean rule_type_recognized(String ruleType) {
    return rule_type_to_rtenum_map.containsKey(get_primary_rule_type(ruleType));
  }

  /**
   * Given a map representing the rules for a column, return true if any of those rules are
   * query-type rules
   */
  private boolean contains_query_rules(Map<String, List<String>> colRules) {
    for (String ruleType : colRules.keySet()) {
      for (String subRuleType : split_on_pipes(ruleType)) {
        if (rule_type_to_rtenum_map.get(subRuleType).getRuleCat() == RCatEnum.QUERY) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Given a string describing one of the classes in the ontology, in either the form of an IRI, an
   * abbreviated IRI, or an rdfs:label, return the rdfs:label for that class.
   */
  private String get_label_from_term(String term) {
    if (term == null) {
      return null;
    }

    // Remove any surrounding single quotes from the term:
    term = term.replaceAll("^\'|\'$", "");

    // If the term is already a recognized label, then just send it back:
    if (label_to_iri_map.containsKey(term)) {
      return term;
    }

    // Check to see if the term is a recognized IRI (possibly in short form), and if so return its
    // corresponding label:
    for (IRI iri : iri_to_label_map.keySet()) {
      if (iri.toString().equals(term) || iri.getShortForm().equals(term)) {
        return iri_to_label_map.get(iri);
      }
    }

    // If the label isn't recognized, just return null:
    return null;
  }

  /**
   * Given a string describing a term from the ontology, parse it into a class expression expressed
   * in terms of the ontology. If the parsing fails, write a statement to the log at the given log
   * level.
   */
  private OWLClassExpression get_class_expression_from_string(String term, LogLevel logLevel) {
    OWLClassExpression ce;
    try {
      ce = parser.parse(term);
    } catch (OWLParserException e) {
      // If the parsing fails the first time, try surrounding the term in single quotes:
      try {
        ce = parser.parse("'" + term + "'");
      } catch (OWLParserException ee) {
        writelog(
            logLevel,
            "Could not determine class expression from \"%s\".\n\t%s.",
            term,
            e.getMessage().trim());
        return null;
      }
    }
    return ce;
  }

  /**
   * Given a string describing a term from the ontology, parse it into a class expression expressed
   * in terms of the ontology. If the parsing fails, write a warning statement to the log.
   */
  private OWLClassExpression get_class_expression_from_string(String term) {
    return get_class_expression_from_string(term, LogLevel.WARN);
  }

  /**
   * Given a string in the form of a wildcard, and a list of strings representing a row of the CSV,
   * return the rdfs:label contained in the position of the row indicated by the wildcard.
   */
  private String get_wildcard_contents(String wildcard, List<String> row) throws Exception {
    if (!wildcard.startsWith("%")) {
      writelog(LogLevel.ERROR, "Invalid wildcard: \"%s\".", wildcard);
      return null;
    }

    int colIndex = Integer.parseInt(wildcard.substring(1)) - 1;
    if (colIndex >= row.size()) {
      throw new Exception(
          String.format(columnOutOfRangeError, tbl_col_index + 1, wildcard, row.size()));
    }

    String term = row.get(colIndex);
    if (term == null || term.trim().equals("")) {
      writelog(
          LogLevel.INFO,
          "Failed to retrieve label from wildcard: %s. No term at position %d of this row.",
          wildcard,
          colIndex + 1);
      return null;
    }

    return term.trim();
  }

  /**
   * Given a string, possibly containing wildcards, and a list of strings representing a row of the
   * CSV, return a string in which all of the wildcards in the input string have been replaced by
   * the rdfs:labels corresponding to the content in the positions of the row that they indicate.
   */
  private List<String> interpolate_rule(String rule, List<String> row) throws Exception {
    // This is what will be returned:
    List<String> interpolatedRules = new ArrayList();

    // If the rule only has whitespace in it, return an empty string back to the caller:
    if (rule.trim().equals("")) {
      interpolatedRules.add("");
      return interpolatedRules;
    }

    // Look for wildcards within the given rule. These will be of the form %d where d is the number
    // of the cell the wildcard is pointing to (e.g. %1 is the first cell). Then create a map from
    // wildcard numbers to the terms that they point to, which we extract from the cell
    // indicated by the wildcard number. In general the terms will be split by pipes within a
    // cell. E.g. if the wildcard is %1 and the first cell contains 'term1|term2|term3' then add an
    // entry to the wildcard map like: 1 -> ['term1', 'term2', 'term3'].
    Matcher m = Pattern.compile("%(\\d+)").matcher(rule);
    Map<Integer, String[]> wildCardMap = new HashMap();
    while (m.find()) {
      int key = Integer.parseInt(m.group(1));
      if (!wildCardMap.containsKey(key)) {
        String wildcard = get_wildcard_contents(m.group(), row);
        String[] terms = wildcard != null ? split_on_pipes(wildcard) : new String[] {null};
        wildCardMap.put(key, terms);
      }
    }

    // If the wildcard map is empty then the rule contained no wildcards. Just return it as it is:
    if (wildCardMap.isEmpty()) {
      interpolatedRules.add(rule);
      return interpolatedRules;
    }

    // Now interpolate the rule using the wildcard map. If any of the wildcards points to a cell
    // with multiple terms, then we duplicate the rule for each term pointed to. Finally we return
    // all of the rules generated.
    for (int i : wildCardMap.keySet()) {
      if (interpolatedRules.isEmpty()) {
        // If we haven't yet interpolated anything, then base the current interpolation on the rule
        // that has been passed as an argument to the function above, and generate an interpolated
        // rule corresponding to every term corresponding to this key in the wildcard map.
        for (String term : wildCardMap.get(i)) {
          String label = get_label_from_term(term);
          String interpolatedRule =
              rule.replaceAll(
                  String.format("%%%d", i), label == null ? "(" + term + ")" : "'" + label + "'");
          interpolatedRules.add(interpolatedRule);
        }
      } else {
        // If we have already interpolated some rules, then every string that has been interpolated
        // thus far must be interpolated again for every term corresponding to this key in the
        // wildcard map, and the list of interpolated rules is then replaced with the new list.
        List<String> tmpList = new ArrayList();
        for (String term : wildCardMap.get(i)) {
          String label = get_label_from_term(term);
          for (String intStr : interpolatedRules) {
            String interpolatedRule =
                intStr.replaceAll(
                    String.format("%%%d", i), label == null ? "(" + term + ")" : "'" + label + "'");
            tmpList.add(interpolatedRule);
          }
        }
        interpolatedRules = tmpList;
      }
    }

    return interpolatedRules;
  }

  /**
   * Given a string describing the content of a rule and a string describing its rule type, return a
   * simple map entry such that the `key` for the entry is the main clause of the rule, and the
   * `value` for the entry is a list of the rule's when-clauses. Each when-clause is itself stored
   * as an array of three strings, including the subject to which the when-clause is to be applied,
   * the rule type for the when clause, and the actual axiom to be validated against the subject.
   */
  private SimpleEntry<String, List<String[]>> separate_rule(String rule, String ruleType)
      throws Exception {

    // Check if there are any when clauses:
    Matcher m = Pattern.compile("(\\(\\s*when\\s+.+\\))(.*)").matcher(rule);
    String whenClauseStr = null;
    if (!m.find()) {
      // If there is no when clause, then just return back the rule string as it was passed with an
      // empty when clause list:
      writelog(LogLevel.DEBUG, "No when-clauses found in rule: \"%s\".", rule);
      return new SimpleEntry<String, List<String[]>>(rule, new ArrayList<String[]>());
    }

    // Throw an exception if there is no main clause and this is not a PRESENCE rule:
    if (m.start() == 0 && rule_type_to_rtenum_map.get(ruleType).getRuleCat() != RCatEnum.PRESENCE) {
      throw new Exception(String.format(noMainError, tbl_col_index + 1, rule));
    }

    // Extract the actual content of the when-clause.
    whenClauseStr = m.group(1);
    whenClauseStr = whenClauseStr.substring("(when ".length(), whenClauseStr.length() - 1);

    // Don't fail just because there is some extra garbage at the end of the rule, but notify
    // the user about it:
    if (!m.group(2).trim().equals("")) {
      writelog(
          LogLevel.WARN, "Ignoring string \"%s\" at end of rule \"%s\".", m.group(2).trim(), rule);
    }

    // Within each when clause, multiple subclauses separated by ampersands are allowed. Each
    // subclass must be of the form: <Entity> <Rule-Type> <Axiom>, where: <Entity> is a (not
    // necessarily interpolated) string describing either a label or a generalised DL class
    // expression involving labels, and any label names containing spaces are enclosed within
    // single quotes; <Rule-Type> is a possibly hyphenated alphanumeric string (which corresponds
    // to one of the rule types defined above in RTypeEnum); and <Axiom> can take any form.
    // Here we resolve each sub-clause of the when statement into a list of such triples.
    ArrayList<String[]> whenClauses = new ArrayList();
    for (String whenClause : whenClauseStr.split("\\s*&\\s*")) {
      m =
          Pattern.compile(
                  "^([^\'\\s\\(\\)]+|\'[^\'\\(\\)]+\'|\\(.+?\\))"
                      + "\\s+([a-z\\-\\|]+)"
                      + "\\s+(.*)$")
              .matcher(whenClause);

      if (!m.find()) {
        throw new Exception(String.format(malformedWhenClauseError, tbl_col_index + 1, whenClause));
      }
      // Add the triple to the list of when clauses:
      whenClauses.add(new String[] {m.group(1), m.group(2), m.group(3)});
    }

    // Now get the main part of the rule (i.e. the part before the when clause):
    m = Pattern.compile("^(.+)\\s+\\(when\\s").matcher(rule);
    if (m.find()) {
      return new SimpleEntry<String, List<String[]>>(m.group(1), whenClauses);
    }

    // If no main clause is found, then if this is a PRESENCE rule, implicitly assume that the main
    // clause is "true":
    if (rule_type_to_rtenum_map.get(ruleType).getRuleCat() == RCatEnum.PRESENCE) {
      return new SimpleEntry<String, List<String[]>>("true", whenClauses);
    }

    // We should never get here since we have already checked for an empty main clause earlier ...
    writelog(
        LogLevel.ERROR,
        "Encountered unknown error while looking for main clause of rule \"%s\".",
        rule);
    // Return the rule as passed with an empty when clause list:
    return new SimpleEntry<String, List<String[]>>(rule, new ArrayList<String[]>());
  }

  /**
   * Given a list of String arrays describing a list of when-clauses, and a list of Strings
   * describing the row to which these when-clauses belong, validate the when-clauses one by one,
   * returning false if any of them fails to be satisfied, and true if they are all satisfied.
   */
  private boolean validate_when_clauses(List<String[]> whenClauses, List<String> row)
      throws Exception {

    for (String[] whenClause : whenClauses) {
      String subject = whenClause[0].trim();
      // If the subject term is blank, then skip this clause:
      if (subject.equals("")) {
        continue;
      }

      // Make sure all of the rule types in the when clause are of the right category:
      String whenRuleType = whenClause[1];
      for (String whenRuleSubType : split_on_pipes(whenRuleType)) {
        RTypeEnum whenSubRType = rule_type_to_rtenum_map.get(whenRuleSubType);
        if (whenSubRType == null || whenSubRType.getRuleCat() != RCatEnum.QUERY) {
          throw new Exception(
              String.format(
                  invalidWhenTypeError,
                  tbl_col_index + 1,
                  String.join(" ", whenClause),
                  query_type_to_rtenum_map.keySet()));
        }
      }

      // Get the axiom to validate and send the query to the reasoner:
      String axiom = whenClause[2];
      if (!execute_query(subject, axiom, row, whenRuleType)) {
        // If any of the when clauses fail to be satisfied, then we do not need to evaluate any
        // of the other when clauses, or the main clause, since the main clause may only be
        // evaluated when all of the when clauses are satisfied.
        writelog(
            LogLevel.INFO,
            "When clause: \"%s %s %s\" is not satisfied.",
            subject,
            whenRuleType,
            axiom);
        return false;
      } else {
        writelog(
            LogLevel.INFO, "Validated when clause \"%s %s %s\".", subject, whenRuleType, axiom);
      }
    }
    // If we get to here, then all of the when clauses have been satisfied, so return true:
    return true;
  }

  /**
   * Given an OWLNamedIndividual describing a subject individual from the ontology, an
   * OWLClassExpression describing a rule to query that subject individual against, a string
   * representing the query types to use when evaluating the results of the query, and a list of
   * strings describing a row from the CSV: Determine whether, for any of the given query types, the
   * given subject is in the result set returned by the reasoner for that query type. Return true if
   * it is in at least one of these result sets, and false if it is not.
   */
  private boolean execute_individual_query(
      OWLNamedIndividual subjectIndividual,
      OWLClassExpression ruleCE,
      List<String> row,
      String unsplitQueryType)
      throws Exception {

    writelog(
        LogLevel.DEBUG,
        "execute_individual_query(): Called with parameters: "
            + "subjectIndividual: \"%s\", "
            + "ruleCE: \"%s\", "
            + "row: \"%s\", "
            + "query type: \"%s\".",
        subjectIndividual,
        ruleCE,
        row,
        unsplitQueryType);

    // For each of the query types associated with the rule, check to see if the rule is satisfied
    // thus interpreted. If it is, then we return true, since multiple query types are interpreted
    // as a disjunction. If a query type is unrecognized or not applicable to an individual, inform
    // the user but continue on.
    String[] queryTypes = split_on_pipes(unsplitQueryType);
    for (String queryType : queryTypes) {
      if (!rule_type_recognized(queryType)) {
        throw new Exception(
            String.format(
                unrecognizedQueryTypeError, tbl_col_index + 1, queryType, unsplitQueryType));
      }

      RTypeEnum qType = query_type_to_rtenum_map.get(queryType);
      if (qType == RTypeEnum.INSTANCE || qType == RTypeEnum.DIRECT_INSTANCE) {
        NodeSet<OWLNamedIndividual> instancesFound =
            reasoner.getInstances(ruleCE, qType == RTypeEnum.DIRECT_INSTANCE);
        if (instancesFound.containsEntity(subjectIndividual)) {
          return true;
        }
      } else {
        // Spit out an error in this case but continue validating the other rules:
        writelog(
            LogLevel.ERROR,
            "%s validation not possible for OWLNamedIndividual %s.",
            qType.getRuleType(),
            subjectIndividual);
        continue;
      }
    }
    return false;
  }

  /**
   * Given an OWLClass describing a subject class from the ontology, an OWLClassExpression
   * describing a rule to query that subject class against, a string representing the query types to
   * use when evaluating the results of the query, and a list of strings describing a row from the
   * CSV: Determine whether, for any of the given query types, the given subject is in the result
   * set returned by the reasoner for that query type. Return true if it is in at least one of these
   * result sets, and false if it is not.
   */
  private boolean execute_class_query(
      OWLClass subjectClass, OWLClassExpression ruleCE, List<String> row, String unsplitQueryType)
      throws Exception {

    writelog(
        LogLevel.DEBUG,
        "execute_class_query(): Called with parameters: "
            + "subjectClass: \"%s\", "
            + "ruleCE: \"%s\", "
            + "row: \"%s\", "
            + "query type: \"%s\".",
        subjectClass,
        ruleCE,
        row,
        unsplitQueryType);

    // For each of the query types associated with the rule, check to see if the rule is satisfied
    // thus interpreted. If it is, then we return true, since multiple query types are interpreted
    // as a disjunction. If a query type is unrecognized, inform the user but continue on.
    String[] queryTypes = split_on_pipes(unsplitQueryType);
    for (String queryType : queryTypes) {
      if (!rule_type_recognized(queryType)) {
        throw new Exception(
            String.format(
                unrecognizedQueryTypeError, tbl_col_index + 1, queryType, unsplitQueryType));
      }

      RTypeEnum qType = query_type_to_rtenum_map.get(queryType);
      if (qType == RTypeEnum.SUB || qType == RTypeEnum.DIRECT_SUB) {
        // Check to see if the subjectClass is a (direct) subclass of the given rule:
        NodeSet<OWLClass> subClassesFound =
            reasoner.getSubClasses(ruleCE, qType == RTypeEnum.DIRECT_SUB);
        if (subClassesFound.containsEntity(subjectClass)) {
          return true;
        }
      } else if (qType == RTypeEnum.SUPER || qType == RTypeEnum.DIRECT_SUPER) {
        // Check to see if the subjectClass is a (direct) superclass of the given rule:
        NodeSet<OWLClass> superClassesFound =
            reasoner.getSuperClasses(ruleCE, qType == RTypeEnum.DIRECT_SUPER);
        if (superClassesFound.containsEntity(subjectClass)) {
          return true;
        }
      } else if (qType == RTypeEnum.EQUIV) {
        Node<OWLClass> equivClassesFound = reasoner.getEquivalentClasses(ruleCE);
        if (equivClassesFound.contains(subjectClass)) {
          return true;
        }
      } else {
        // Spit out an error in this case but continue validating the other rules:
        writelog(
            LogLevel.ERROR,
            "%s validation not possible for OWLClass %s.",
            qType.getRuleType(),
            subjectClass);
        continue;
      }
    }
    return false;
  }

  /**
   * Given an OWLClassExpression describing an unnamed subject class from the ontology, an
   * OWLClassExpression describing a rule to query that subject class against, a string representing
   * the query types to use when evaluating the results of the query, and a list of strings
   * describing a row from the CSV: Determine whether, for any of the given query types, the given
   * subject is in the result set returned by the reasoner for that query type. Return true if it is
   * in at least one of these result sets, and false if it is not.
   */
  private boolean execute_generalized_class_query(
      OWLClassExpression subjectCE,
      OWLClassExpression ruleCE,
      List<String> row,
      String unsplitQueryType)
      throws Exception, UnsupportedOperationException {

    writelog(
        LogLevel.DEBUG,
        "execute_generalized_class_query(): Called with parameters: "
            + "subjectCE: \"%s\", "
            + "ruleCE: \"%s\", "
            + "row: \"%s\", "
            + "query type: \"%s\".",
        subjectCE,
        ruleCE,
        row,
        unsplitQueryType);

    // For each of the query types associated with the rule, check to see if the rule is satisfied
    // thus interpreted. If it is, then we return true, since multiple query types are interpreted
    // as a disjunction. If a query type is unrecognized, inform the user but continue on.
    String[] queryTypes = split_on_pipes(unsplitQueryType);
    for (String queryType : queryTypes) {
      if (!rule_type_recognized(queryType)) {
        throw new Exception(
            String.format(
                unrecognizedQueryTypeError, tbl_col_index + 1, queryType, unsplitQueryType));
      }

      RTypeEnum qType = query_type_to_rtenum_map.get(queryType);
      if (qType == RTypeEnum.SUB) {
        // Check to see if the subjectClass is a subclass of the given rule:
        OWLSubClassOfAxiom axiom = dataFactory.getOWLSubClassOfAxiom(subjectCE, ruleCE);
        if (reasoner.isEntailed(axiom)) {
          return true;
        }
      } else if (qType == RTypeEnum.SUPER) {
        // Check to see if the subjectClass is a superclass of the given rule:
        OWLSubClassOfAxiom axiom = dataFactory.getOWLSubClassOfAxiom(ruleCE, subjectCE);
        if (reasoner.isEntailed(axiom)) {
          return true;
        }
      } else if (qType == RTypeEnum.EQUIV) {
        OWLEquivalentClassesAxiom axiom =
            dataFactory.getOWLEquivalentClassesAxiom(subjectCE, ruleCE);
        if (reasoner.isEntailed(axiom)) {
          return true;
        }
      } else {
        // Spit out an error in this case but continue validating the other rules:
        writelog(
            LogLevel.ERROR,
            "%s validation not possible for OWLClassExpression %s.",
            qType.getRuleType(),
            subjectCE);
        continue;
      }
    }
    return false;
  }

  /**
   * Given a string describing a subject term, a string describing a rule to query that subject term
   * against, a string representing the query types to use when evaluating the results of the query,
   * and a list of strings describing a row from the CSV: Determine whether, for any of the given
   * query types, the given subject is in the result set returned by the reasoner for that query
   * type. Return true if it is in at least one of these result sets, and false if it is not.
   */
  private boolean execute_query(
      String subject, String rule, List<String> row, String unsplitQueryType) throws Exception {

    writelog(
        LogLevel.DEBUG,
        "execute_query(): Called with parameters: "
            + "subject: \"%s\", "
            + "rule: \"%s\", "
            + "row: \"%s\", "
            + "query type: \"%s\".",
        subject,
        rule,
        row,
        unsplitQueryType);

    // Get the class expression corresponfing to the rule that has been passed:
    OWLClassExpression ruleCE = get_class_expression_from_string(rule);
    if (ruleCE == null) {
      report("Unable to parse rule \"%s %s\".", unsplitQueryType, rule);
      return false;
    }

    // Try to extract the label corresponding to the subject term:
    String subjectLabel = get_label_from_term(subject);
    if (subjectLabel != null) {
      // Figure out if it is an instance or a class and run the appropriate query
      IRI subjectIri = label_to_iri_map.get(subjectLabel);
      OWLEntity subjectEntity = OntologyHelper.getEntity(ontology, subjectIri);
      try {
        OWLNamedIndividual subjectIndividual = subjectEntity.asOWLNamedIndividual();
        return execute_individual_query(subjectIndividual, ruleCE, row, unsplitQueryType);
      } catch (OWLRuntimeException e) {
        try {
          OWLClass subjectClass = subjectEntity.asOWLClass();
          return execute_class_query(subjectClass, ruleCE, row, unsplitQueryType);
        } catch (OWLRuntimeException ee) {
          // This actually should not happen, since if the subject has a label it should either
          // be a named class or a named individual:
          writelog(
              LogLevel.ERROR,
              "While validating \"%s\" against \"%s %s\", encountered: %s",
              subject,
              unsplitQueryType,
              rule,
              ee);
          return false;
        }
      }
    } else {
      // If no label corresponding to the subject term can be found, then try and parse it as a
      // class expression and run a generalised query on it:
      OWLClassExpression subjectCE = get_class_expression_from_string(subject);
      if (subjectCE == null) {
        writelog(LogLevel.ERROR, "Unable to parse subject \"%s\".", subject);
        return false;
      }

      try {
        return execute_generalized_class_query(subjectCE, ruleCE, row, unsplitQueryType);
      } catch (UnsupportedOperationException e) {
        writelog(
            LogLevel.ERROR,
            "Generalized class expression queries are not supported by this reasoner.");
        return false;
      }
    }
  }

  /**
   * Given a string describing a rule, a rule of the type PRESENCE, and a string representing a cell
   * from the CSV, determine whether the cell satisfies the given presence rule (e.g. is-required,
   * is-empty).
   */
  private void validate_presence_rule(String rule, RTypeEnum rType, String cell)
      throws Exception, IOException {

    writelog(
        LogLevel.DEBUG,
        "validate_presence_rule(): Called with parameters: "
            + "rule: \"%s\", "
            + "rule type: \"%s\", "
            + "cell: \"%s\".",
        rule,
        rType.getRuleType(),
        cell);

    // Presence-type rules (is-required, is-excluded) must be in the form of a truth value:
    if ((Arrays.asList("true", "t", "1", "yes", "y").indexOf(rule.toLowerCase()) == -1)
        && (Arrays.asList("false", "f", "0", "no", "n").indexOf(rule.toLowerCase()) == -1)) {
      throw new Exception(
          String.format(invalidPresenceRuleError, tbl_col_index + 1, rule, rType.getRuleType()));
    }

    // If the restriction isn't "true" then there is nothing to do. Just return:
    if (Arrays.asList("true", "t", "1", "yes", "y").indexOf(rule.toLowerCase()) == -1) {
      writelog(
          LogLevel.DEBUG, "Nothing to validate for rule: \"%s %s\"", rType.getRuleType(), rule);
      return;
    }

    switch (rType) {
      case REQUIRED:
        if (cell.trim().equals("")) {
          report(
              "Cell is empty but rule: \"%s %s\" does not allow this.", rType.getRuleType(), rule);
          return;
        }
        break;
      case EXCLUDED:
        if (!cell.trim().equals("")) {
          report(
              "Cell is non-empty (\"%s\") but rule: \"%s %s\" does not allow this.",
              cell, rType.getRuleType(), rule);
          return;
        }
        break;
      default:
        writelog(
            LogLevel.ERROR,
            "%s validation of rule type: \"%s\" is not yet implemented.",
            rType.getRuleCat(),
            rType.getRuleType());
        return;
    }
    writelog(LogLevel.INFO, "Validated \"%s %s\" against \"%s\".", rType.getRuleType(), rule, cell);
  }

  /**
   * Given a string describing a cell from the CSV, a string describing a rule to be applied against
   * that cell, a string describing the type of that rule, and a list of strings describing the row
   * containing the given cell, validate the cell, indicating any validation errors via the output
   * writer (or XLSX workbook).
   */
  private void validate_rule(String cell, String rule, List<String> row, String ruleType)
      throws Exception, IOException {

    writelog(
        LogLevel.DEBUG,
        "validate_rule(): Called with parameters: "
            + "cell: \"%s\", "
            + "rule: \"%s\", "
            + "row: \"%s\", "
            + "rule type: \"%s\".",
        cell,
        rule,
        row,
        ruleType);

    writelog(LogLevel.INFO, "Validating rule \"%s %s\" against \"%s\".", ruleType, rule, cell);
    if (!rule_type_recognized(ruleType)) {
      throw new Exception(String.format(unrecognizedRuleTypeError, tbl_col_index + 1, ruleType));
    }

    // Separate the given rule into its main clause and optional when clauses:
    SimpleEntry<String, List<String[]>> separatedRule = separate_rule(rule, ruleType);

    // Evaluate and validate any when clauses for this rule first:
    if (!validate_when_clauses(separatedRule.getValue(), row)) {
      writelog(LogLevel.DEBUG, "Not all when clauses have been satisfied. Skipping main clause");
      return;
    }

    // Once all of the when clauses have been validated, get the RTypeEnum representation of the
    // primary rule type of this rule:
    RTypeEnum primRType = rule_type_to_rtenum_map.get(get_primary_rule_type(ruleType));

    // If the primary rule type for this rule is not in the QUERY category, process it at this step
    // and return control to the caller. The further steps below are only needed when queries are
    // going to be sent to the reasoner.
    if (primRType.getRuleCat() != RCatEnum.QUERY) {
      validate_presence_rule(separatedRule.getKey(), primRType, cell);
      return;
    }

    // If the cell contents are empty, just return to the caller silently (if the cell is not
    // expected to be empty, this will have been caught by one of the presence rules in the
    // previous step, assuming such a rule is constraining the column).
    if (cell.trim().equals("")) return;

    // Get the axiom that the cell will be validated against:
    String axiom = separatedRule.getKey();

    // Send the query to the reasoner:
    boolean result = execute_query(cell, axiom, row, ruleType);
    if (!result) {
      report("Validation failed for rule: \"%s %s %s\".", cell, ruleType, axiom);
    } else {
      writelog(LogLevel.INFO, "Validated: \"%s %s %s\".", cell, ruleType, axiom);
    }
  }

  /**
   * Given a string representing a filesystem path to a table, set up the appropriate output for
   * that path; this will be different depending on the file extension of the path (XLSX, HTML, TXT)
   * and STDOUT will be used if the output path is null.
   */
  public void setup_output(String outputPath) throws FileNotFoundException, IOException {
    // If the output path is null, do nothing. Elsewhere we will take that to indicate we should be
    // writing to STDOUT. If the output path is not null: Then if it ends in ".xlsx" initialise an
    // XLSX workbook, otherwise initialise an output writer to direct output to the location
    // indicated by the output path. In the latter case, if the output path ends with ".html", then
    // also initialise the jinja context we will use to generate the html output.
    if (outputPath != null) {
      if (outputPath.toLowerCase().endsWith(".xlsx")) {
        workbook = new XSSFWorkbook();
        // Create a single worksheet inside the workbook and initialise an output stream to which we
        // will later write the contents of the workbook.
        workbook.createSheet();
        xlsxFileOutputStream = new FileOutputStream(outputPath);
      } else {
        writer = new FileWriter(outputPath);
        if (outputPath.toLowerCase().endsWith(".html")) {
          jinjaTblContext = new HashMap();
        }
      }
    }
  }

  /**
   * Given the filesystem path (used only as an identifier) to a table, a list of lists of strings
   * representing the rows of the table, an output path, and a flag indicating whether the output
   * (if it is HTML) should be a standalone file: Extract the rules to use for validation from the
   * table, and then validate the table using those extracted rules, row by row and column by column
   * within each row, using the reasoner when required to perform lookups to the ontology,
   * indicating any validation errors via the output writer (or an XLSX workbook in the output path
   * ends with the suffix ".xlsx").
   */
  public void validate(
      String tblPath, List<List<String>> tblData, boolean standalone, String outputPath)
      throws Exception {

    // Set up the right output structures to use based on what has been passed in the output path:
    setup_output(outputPath);
    // If there is no writer and no workbook then validation output will go to stdout, so we write
    // a line here to identify the table that this output belongs to in lieu of a filename:
    if (writer == null && workbook == null) {
      System.out.println(
          String.format(
              "Validating %s.%s ...",
              FilenameUtils.getBaseName(tblPath), FilenameUtils.getExtension(tblPath)));
    }

    // Extract the header and rules rows from the CSV data and map the column names to their
    // associated lists of rules:
    List<String> headerRow = tblData.remove(0);
    List<String> rulesRow = tblData.remove(0);
    HashMap<String, Map<String, List<String>>> headerToRuleMap = new HashMap();
    for (int i = 0; i < headerRow.size(); i++) {
      String rawRule = i < rulesRow.size() ? rulesRow.get(i) : "";
      headerToRuleMap.put(headerRow.get(i), parse_rules(rawRule));
    }

    if (workbook != null) {
      // If we are writing to an XLSX workbook, then add the header and rule rows here:
      add_non_data_rows_to_xlsx(Arrays.asList(headerRow, rulesRow));
    } else if (jinjaTblContext != null) {
      // If we are writing to an HTML file, then prepare the jinja context by adding in the header
      // and rules rows, and initialising memory for the cells in the data rows that will be added
      // later:
      prepare_jinja_context(headerRow, rulesRow, tblData.size());
    }

    // Validate the data row by row, and column by column by column within a row. tbl_row_index and
    // tbl_col_index are class variables that will later be used to provide information to the user
    // about the current location within the CSV file when logging info and reporting errors.
    for (tbl_row_index = 0; tbl_row_index < tblData.size(); tbl_row_index++) {
      List<String> row = tblData.get(tbl_row_index);
      if (!has_content(row)) {
        writelog(false, LogLevel.DEBUG, "Skipping empty row %d", tbl_row_index);
        continue;
      }

      for (tbl_col_index = 0; tbl_col_index < headerRow.size(); tbl_col_index++) {
        // Get the contents of the current cell:
        String cellString = tbl_col_index < row.size() ? row.get(tbl_col_index) : "";

        // Get the rules for the current column:
        String colName = headerRow.get(tbl_col_index);
        Map<String, List<String>> colRules = headerToRuleMap.get(colName);

        // If there is an XLSX workbook or a jinja context to write to, write the contents of the
        // current cell to it:
        if (workbook != null) {
          // If there is an XLSX workbook to write to, write the contents of the current cell to it:
          write_xlsx(cellString);
        } else if (jinjaTblContext != null) {
          // If there is a jinja context to write to, write the contents of the current cell to it:
          write_html(cellString, !contains_query_rules(colRules));
        }

        // Extract all the data entries contained within the current cell:
        String[] cellData = split_on_pipes(cellString.trim());

        // If there are no rules for this column, then skip the validation for this cell (the entire
        // column to which the cell belongs is interpreted as 'commented out'):
        if (colRules.isEmpty()) continue;

        // For each of the rules applicable to this column, validate each entry in the cell
        // against it:
        for (String ruleType : colRules.keySet()) {
          for (String rule : colRules.get(ruleType)) {
            // The relation between rules, as given in the input table, and interpolated rules
            // is many to one, because wildcards can refer to cells with multiple entries.
            List<String> interpolatedRules = interpolate_rule(rule, row);
            for (String interpolatedRule : interpolatedRules) {
              for (String data : cellData) {
                validate_rule(data, interpolatedRule, row, ruleType);
              }
            }
          }
        }
      }
    }

    // If the jinja context is not null, then use it to format the output as a HTML file:
    if (jinjaTblContext != null) {
      generate_html_report(standalone);
    }

    // Close output streams, etc.:
    tearDown();
  }
}