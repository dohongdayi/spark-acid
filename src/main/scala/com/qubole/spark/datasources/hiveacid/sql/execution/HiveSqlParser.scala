package com.qubole.spark.datasources.hiveacid.sql.execution

import com.qubole.spark.datasources.hiveacid.sql.catalyst.parser._
import org.antlr.v4.runtime._
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.apache.commons.lang3.StringUtils.{startsWithIgnoreCase, stripStart}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.catalyst.parser.{ParseErrorListener, ParseException, ParserInterface}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.trees.Origin
import org.apache.spark.sql.execution.SparkSqlParser
import org.apache.spark.sql.internal.{SQLConf, VariableSubstitution}
import org.apache.spark.sql.types.{DataType, StructType}

/**
 * Concrete parser for Hive SQL statements.
 */
case class HiveSqlParser(session: SparkSession, sparkParser: ParserInterface) extends ParserInterface with Logging {

  override def parseExpression(sqlText: String): Expression = sparkParser.parseExpression(sqlText)

  override def parseTableIdentifier(sqlText: String): TableIdentifier = sparkParser.parseTableIdentifier(sqlText)

  override def parseFunctionIdentifier(sqlText: String): FunctionIdentifier = sparkParser.parseFunctionIdentifier(sqlText)

  override def parseTableSchema(sqlText: String): StructType = sparkParser.parseTableSchema(sqlText)

  override def parseDataType(sqlText: String): DataType = sparkParser.parseDataType(sqlText)

  private val substitutor: VariableSubstitution = {
    val field = classOf[SparkSqlParser].getDeclaredField("substitutor")
    field.setAccessible(true)
    field.get(sparkParser).asInstanceOf[VariableSubstitution]
  }

  // FIXME scala reflection would be better
  private val conf: SQLConf = {
    val field = classOf[VariableSubstitution].getDeclaredField("org$apache$spark$sql$internal$VariableSubstitution$$conf")
    field.setAccessible(true)
    field.get(substitutor).asInstanceOf[SQLConf]
  }

  private val hiveSqlPrefixes = Seq("DELETE", "UPDATE")
  private val hiveAstBuilder = new HiveSqlAstBuilder(conf)

  override def parsePlan(sqlText: String): LogicalPlan = {
    val sqlTextTrim = stripStart(sqlText, null)
    if (hiveSqlPrefixes.exists(startsWithIgnoreCase(sqlTextTrim, _))) {
      return parsePlanHive(sqlText)
    }
    sparkParser.parsePlan(sqlText)
  }

  /**
   *  An adaptation of [[org.apache.spark.sql.catalyst.parser.AbstractSqlParser#parsePlan]]
   */
  protected def parsePlanHive(sqlText: String): LogicalPlan = {
    parseHive(sqlText) { parser =>
      hiveAstBuilder.visitSingleStatement(parser.singleStatement()) match {
        case plan: LogicalPlan => plan
        case _ =>
          val position = Origin(None, None)
          throw new ParseException(Option(sqlText), "Unsupported SQL statement", position, position)
      }
    }
  }

  /**
   *  An adaptation of [[org.apache.spark.sql.execution.SparkSqlParser#parse]]
   *  and [[org.apache.spark.sql.catalyst.parser.AbstractSqlParser#parse]]
   */
  protected def parseHive[T](sqlText: String)(toResult: SqlHiveParser => T): T = {
    val command = substitutor.substitute(sqlText)
    logDebug(s"Parsing command: $command")


    val lexer = new SqlHiveLexer(new UpperCaseCharStream(CharStreams.fromString(command)))
    lexer.removeErrorListeners()
    lexer.addErrorListener(ParseErrorListener)
    lexer.legacy_setops_precedence_enbled = SQLConf.get.setOpsPrecedenceEnforced

    val tokenStream = new CommonTokenStream(lexer)
    val parser = new SqlHiveParser(tokenStream)
    parser.addParseListener(PostProcessor)
    parser.removeErrorListeners()
    parser.addErrorListener(ParseErrorListener)
    parser.legacy_setops_precedence_enbled = SQLConf.get.setOpsPrecedenceEnforced

    try {
      try {
        // first, try parsing with potentially faster SLL mode
        parser.getInterpreter.setPredictionMode(PredictionMode.SLL)
        toResult(parser)
      }
      catch {
        case e: ParseCancellationException =>
          // if we fail, parse with LL mode
          tokenStream.seek(0) // rewind input stream
          parser.reset()

          // Try Again.
          parser.getInterpreter.setPredictionMode(PredictionMode.LL)
          toResult(parser)
      }
    }
    catch {
      case e: ParseException if e.command.isDefined =>
        throw e
      case e: ParseException =>
        throw e.withCommand(command)
      case e: AnalysisException =>
        val position = Origin(e.line, e.startPosition)
        throw new ParseException(Option(command), e.message, position, position)
    }
  }
}