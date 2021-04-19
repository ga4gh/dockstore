package io.dockstore.common


import cats.syntax.validated._
import com.typesafe.config.ConfigFactory
import common.Checked
import common.validation.Checked._
import common.validation.ErrorOr.ErrorOr
import cromwell.core.path.DefaultPathBuilder
import cromwell.languages.LanguageFactory
import cromwell.languages.util.{ImportResolver, LanguageFactoryUtil}
import cromwell.languages.util.ImportResolver.{DirectoryResolver, HttpResolver, ImportResolver, ResolvedImportBundle}
import languages.wdl.biscayne.WdlBiscayneLanguageFactory
import languages.wdl.draft2.WdlDraft2LanguageFactory
import languages.wdl.draft3.WdlDraft3LanguageFactory
import org.slf4j.LoggerFactory
import shapeless.Inl
import spray.json.DefaultJsonProtocol._
import spray.json._
import wdl.draft2.model.WdlWomExpression
import wdl.draft3.parser.WdlParser
import wdl.model.draft3.elements.ExpressionElement.StringLiteral
import wdl.transforms.base.wdlom2wom.expression.WdlomWomExpression
import wom.ResolvedImportRecord
import wom.callable.MetaValueElement.MetaValueElementString
import wom.callable.{CallableTaskDefinition, ExecutableCallable, WorkflowDefinition}
import wom.executable.WomBundle
import wom.expression.WomExpression
import wom.graph._
import wom.types.{WomCompositeType, WomOptionalType, WomType}
import java.nio.file.{Files, Paths}
import java.util
import java.util.Optional

import scala.collection.JavaConverters
import scala.collection.JavaConverters._
import scala.util.Try


/**
  * A bridge class for interacting with the WDL documents
  *
  * Note: For simplicity, uses draft-3/v1.0 for throwing syntax errors from any language
  */
class WdlBridge {
  var secondaryWdlFiles = new util.HashMap[String, String]()

  def main(args: Array[String]): Unit = {
    println("WdlBridge")
  }

  /**
    * Set the secondary files (imports)
    * @param secondaryFiles
    */
  def setSecondaryFiles(secondaryFiles: util.HashMap[String, String]): Unit = {
    secondaryWdlFiles = secondaryFiles
  }

  /**
    * Validates the workflow given by filePath
    * @param filePath absolute path to file
    */
  @throws(classOf[WdlParser.SyntaxError])
  def validateWorkflow(filePath: String, sourceFilePath: String) = {
    val bundle = getBundle(filePath, sourceFilePath)

    if (!bundle.primaryCallable.isDefined) {
      throw new WdlParser.SyntaxError("This file is missing a workflow declaration.")
    }
  }

  /**
    * Validates the tool given by filePath
    * @param filePath absolute path to file
    */
  @throws(classOf[WdlParser.SyntaxError])
  def validateTool(filePath: String, sourceFilePath: String) = {
    validateWorkflow(filePath, sourceFilePath)
    val executableCallable = convertFilePathToExecutableCallable(filePath, sourceFilePath)
    val numberOfTaskCalls = executableCallable.taskCallNodes.seq.size

    if (numberOfTaskCalls > 1) {
      throw new WdlParser.SyntaxError("A WDL tool can only call one task. This file calls more than one task. Did you mean to register a workflow?")
    }

    executableCallable.taskCallNodes
      .foreach(call => {
        val dockerAttribute = call.callable.runtimeAttributes.attributes.get("docker")
        if (!dockerAttribute.isDefined) {
          throw new WdlParser.SyntaxError(call.identifier.localName + " requires an associated docker container to make this a valid Dockstore tool.")
        }
      })
  }

  /**
    * Retrieves the metadata object for a given workflow
    * @param filePath absolute path to file
    * @throws wdl.draft3.parser.WdlParser.SyntaxError
    * @return list of metadata mappings
    */
  @throws(classOf[WdlParser.SyntaxError])
  def getMetadata(filePath: String, sourceFilePath: String) = {
    val bundle = getBundle(filePath, sourceFilePath)
    val metadataList = new util.ArrayList[util.Map[String, String]]()
    bundle.allCallables.foreach(callable => {
      callable._2 match {
        case w: WorkflowDefinition => {
          val workflowCallable = callable._2
          val workflowInstance = workflowCallable.asInstanceOf[WorkflowDefinition]
          val workflowMetadata = workflowInstance.meta
          // Metadata is sometimes not a string (booleans for example), ignoring those
          val convertedWorkflowMap = workflowMetadata.collect{ case (k, v) if v.isInstanceOf[MetaValueElementString] => (k, v.asInstanceOf[MetaValueElementString].value)}
          val metadata = JavaConverters.mapAsJavaMap(convertedWorkflowMap)
          if (!metadata.isEmpty) {
            metadataList.add(metadata)
          }
        }
        case c: CallableTaskDefinition => {
          val taskCallable = callable._2
          val taskInstance = taskCallable.asInstanceOf[CallableTaskDefinition]
          val taskMetadata = taskInstance.meta
          val convertedTaskMetadataMap = taskMetadata.map{ case (k, v) => (k, v.asInstanceOf[MetaValueElementString].value)}
          val metadata = JavaConverters.mapAsJavaMap(convertedTaskMetadataMap)
          if (!metadata.isEmpty) {
            metadataList.add(metadata)
          }
        }
      }
    })
    metadataList
  }

  /**
    * Create a map of file inputs names to paths
    * @param filePath absolute path to file
    * @throws wdl.draft3.parser.WdlParser.SyntaxError
    * @return mapping of file input name to type
    */
  @throws(classOf[WdlParser.SyntaxError])
  def getInputFiles(filePath: String, sourceFilePath: String):  util.HashMap[String, String] = {
    val fileStrings: List[String] = List("File", "File?", "Array[File]", "Array[File]?")
    val inputList = new util.HashMap[String, String]()
    val bundle = getBundle(filePath, sourceFilePath)
    val primaryCallable = bundle.primaryCallable.orNull
    if (primaryCallable == null) {
      throw new WdlParser.SyntaxError("Error parsing WDL file.")
    }

    val workflowName = primaryCallable.name
    primaryCallable.inputs
      .filter(input => fileStrings.contains(input.womType.stableName.toString))
      .foreach(input => inputList.put(workflowName + "." + input.name, input.womType.stableName.toString))
    inputList
  }

  // TODO: Remove this method after Dockstore CLI no longer calls it
  @throws(classOf[WdlParser.SyntaxError])
  def getInputFiles(filePath: String):  util.HashMap[String, String] = {
    getInputFiles(filePath, "/") // Not ideal, need for CLI
  }

  /**
    * Create a list of all output files for the workflow
    * @param filePath absolute path to file
    * @throws wdl.draft3.parser.WdlParser.SyntaxError
    * @return list of output file names
    */
  @throws(classOf[WdlParser.SyntaxError])
  def getOutputFiles(filePath: String, sourceFilePath: String): util.List[String] = {
    val outputList = new util.ArrayList[String]()
    val bundle = getBundle(filePath, sourceFilePath)
    val primaryCallable = bundle.primaryCallable.orNull
    if (primaryCallable == null) {
      throw new WdlParser.SyntaxError("Error parsing WDL file.")
    }
    val workflowName = primaryCallable.name
    primaryCallable.outputs
      .filter(output => output.womType.stableName.toString.equals("File") || output.womType.stableName.toString.equals("Array[File]"))
      .foreach(output => outputList.add(workflowName + "." + output.name))
    outputList
  }

  // TODO: Remove this method after Dockstore CLI no longer calls it
  @throws(classOf[WdlParser.SyntaxError])
  def getOutputFiles(filePath: String): util.List[String] = {
    getOutputFiles(filePath, "/")
  }
  /**
    * Create a mapping of import namespace to uri
    * Does not work with new parsing code, may be phased out
    * @param filePath absolute path to file
    * @return map of call names to import path
    */
  def getImportMap(filePath: String, sourceFilePath: String): util.LinkedHashMap[String, String] = {
    val importMap = new util.LinkedHashMap[String, String]()
    val executableCallable = convertFilePathToExecutableCallable(filePath, sourceFilePath)
    executableCallable.taskCallNodes
      .foreach(call => {
        val callName = call.identifier.localName.value
        val path = null
        importMap.put(callName, path)
      })
    importMap
  }

  /**
    * Create a mapping of calls to dependencies
    * @param filePath absolute path to file
    * @return mapping of call to a list of dependencies
    */
  def getCallsToDependencies(filePath: String, sourceFilePath: String): util.LinkedHashMap[String, util.List[String]] = {
    val dependencyMap = new util.LinkedHashMap[String, util.List[String]]()
    val executableCallable = convertFilePathToExecutableCallable(filePath, sourceFilePath)

    executableCallable.taskCallNodes
      .foreach(call => {
        val callName = call.identifier.localName.value
        dependencyMap.put("dockstore_" + callName, new util.ArrayList[String]())
      })

    executableCallable.taskCallNodes
        .foreach(call => {
          val dependencies = new util.ArrayList[String]()
          call.inputDefinitionMappings
            .foreach(inputMap => {
              val maybePorts = inputMap._2 match {
                case Inl(head) => Some(head.graphNode.inputPorts)
                case a => None
              }
              maybePorts.foreach((inputPorts: Set[GraphNodePort.InputPort]) => {
                inputPorts
                  .foreach(inputPort => {
                    val inputName = inputPort.name
                    val lastPeriodIndex = inputName.lastIndexOf(".")
                    if (lastPeriodIndex != -1) {
                      dependencies.add("dockstore_" + inputName.substring(0, lastPeriodIndex))
                    }
                  })
              })
            })
          dependencyMap.replace("dockstore_" + call.identifier.localName.value, dependencies)

        })

    dependencyMap
  }

  /**
    * Create a mapping of calls to docker images
    * @param filePath absolute path to file
    * @return mapping of call names to docker
    */
  @throws(classOf[WdlParser.SyntaxError])
  def getCallsToDockerMap(filePath: String, sourceFilePath: String): util.LinkedHashMap[String, DockerParameter] = {
    val executableCallable = convertFilePathToExecutableCallable(filePath, sourceFilePath)
    getCallsToDockerMap(executableCallable)
  }


  def getCallsToDockerMap(executableCallable: ExecutableCallable) = {

    def imageReference(call: CommandCallNode, dockerAttribute: Option[WomExpression]) = {
      dockerAttribute match {
        // We get WdlomWomExpression for 1.x
        case Some(WdlomWomExpression(s: StringLiteral, _)) =>  DockerImageReference.LITERAL
        case Some(WdlomWomExpression(_, _)) =>  DockerImageReference.DYNAMIC

        // This is for WDL pre-1.0
        case Some(WdlWomExpression(wdlExpression, _)) => {
          wdlExpression.ast match {
            case t: wdl.draft2.parser.WdlParser.Terminal =>
              if ("string" == t.terminal_str) DockerImageReference.LITERAL // t.terminal_str is "identifier" if it's not a literal string
              else DockerImageReference.DYNAMIC
            case _ => {
              WdlBridge.logger.warn(s"Unexpected ast: ${wdlExpression.ast.toPrettyString}")
              DockerImageReference.UNKNOWN
            }
          }

        }
        case Some(_) => {
          WdlBridge.logger.error(s"Unexpected dockerAttribute ${dockerAttribute.mkString}")
          DockerImageReference.UNKNOWN
        }
        case None => DockerImageReference.UNKNOWN // not applicable really
      }
    }

    val callsToDockerMap = new util.LinkedHashMap[String, DockerParameter]()
    executableCallable.taskCallNodes
      .foreach(call => {
        val dockerAttribute = call.callable.runtimeAttributes.attributes.get("docker")
        val callName = "dockstore_" + call.identifier.localName.value
        val dockerString = dockerAttribute.map(_.sourceString.replaceAll("\"", "")).getOrElse("")
        callsToDockerMap.put(callName, DockerParameter(dockerString, imageReference(call, dockerAttribute)))
      })
    callsToDockerMap
  }

  /**
    * Get a parameter file as a string
    *
    * @param filePath absolute path to file
    * @throws wdl.draft3.parser.WdlParser.SyntaxError
    * @return stub parameter file for the workflow
    */
  @throws(classOf[WdlParser.SyntaxError])
  def getParameterFile(filePath: String, sourceFilePath:String): String = {
    val executableCallable = convertFilePathToExecutableCallable(filePath, sourceFilePath)
    executableCallable.graph.externalInputNodes.toJson(inputNodeWriter(true)).prettyPrint
  }

  // TODO: Remove this method after Dockstore CLI no longer calls it
  @throws(classOf[WdlParser.SyntaxError])
  def getParameterFile(filePath: String): String = {
    getParameterFile(filePath, "/") // Not ideal, doing this for now because of dependencies with CLI
  }

  @throws(classOf[WdlParser.SyntaxError])
  private def convertFilePathToExecutableCallable(filePath: String, sourceFilePath: String): ExecutableCallable = {
    val bundle = getBundle(filePath, sourceFilePath)
    convertBundleToExecutableCallable(bundle)
  }

  def convertBundleToExecutableCallable(bundle: WomBundle) = {
    val executableCallable = bundle.toExecutableCallable.right.getOrElse(null)
    if (executableCallable == null) {
      throw new WdlParser.SyntaxError("Error parsing WDL file")
    }
    executableCallable
  }

  private def inputNodeWriter(showOptionals: Boolean): JsonWriter[Set[ExternalGraphInputNode]] = set => {

    val valueMap: Seq[(String, JsValue)] = set.toList collect {
      case RequiredGraphInputNode(_, womType, nameInInputSet, _) => nameInInputSet -> womTypeToJson(womType, None)
      case OptionalGraphInputNode(_, womOptionalType, nameInInputSet, _) if showOptionals => nameInInputSet -> womTypeToJson(womOptionalType, None)
      case OptionalGraphInputNodeWithDefault(_, womType, default, nameInInputSet, _) if showOptionals => nameInInputSet -> womTypeToJson(womType, Option(default))
    }

    valueMap.toMap.toJson
  }

  private def womTypeToJson(womType: WomType, default: Option[WomExpression]): JsValue = (womType, default) match {
    case (WomCompositeType(typeMap, _), _) => JsObject(
      typeMap.map { case (name, wt) => name -> womTypeToJson(wt, None) }
    )
    case (_, Some(d)) => JsString(s"${womType.stableName} (optional, default = ${d.sourceString})")
    case (_: WomOptionalType, _) => JsString(s"${womType.stableName} (optional)")
    case (_, _) => JsString(s"${womType.stableName}")
  }

  /**
    * Get the WomBundle for a workflow
    * @param filePath absolute path to file
    * @return WomBundle
    */
  def getBundle(filePath: String, sourceFilePath: String): WomBundle = {
    val fileContent = readFile(filePath)
    getBundleFromContent(fileContent, filePath, sourceFilePath)
  }

  /**
    * Get the WomBundle for a workflow given the workflow content
    * To be used when we don't have a file stored locally
    * @param content content of file
    * @param filePath path to temp file on disk
    * @param sourceFilePath the path of the source file
    * @return WomBundle
    */
  def getBundleFromContent(content: String, filePath: String, sourceFilePath: String): WomBundle = {
    val factory = getLanguageFactory(content)
    val filePathObj = DefaultPathBuilder.build(filePath).get
    // Resolve from mapping, local filesystem, or http import
    val mapResolver = MapResolver(sourceFilePath)
    mapResolver.setSecondaryFiles(secondaryWdlFiles)
    lazy val importResolvers: List[ImportResolver] =
      DirectoryResolver.localFilesystemResolvers(Some(filePathObj)) :+ HttpResolver(relativeTo = None) :+ mapResolver
    try {
      val bundle = factory.getWomBundle(content, workflowSourceOrigin = None,  "{}", importResolvers, List(factory))
      if (bundle.isRight) {
        bundle.getOrElse(null)
      } else {
        throw new WdlParser.SyntaxError(bundle.left.get.head)
      }
    } catch {
      case ex: WdlParser.SyntaxError => throw ex
      case ex: Exception => {
        WdlBridge.logger.error("Unexpected error parsing WDL", ex)
        throw new WdlParser.SyntaxError("There was an error creating a Wom Bundle for the workflow.")
      }
    }
  }

  /**
    * Retrieve the language factory for the given primary descriptor file
    * @param fileContent Content of the primary workflow file
    * @return Correct language factory based on the version of WDL
    */
  def getLanguageFactory(fileContent: String) : LanguageFactory = {
    val languageFactory =
      List(
        new WdlDraft3LanguageFactory(ConfigFactory.empty()),
        new WdlBiscayneLanguageFactory(ConfigFactory.empty()))
      .find(_.looksParsable(fileContent))
      .getOrElse(new WdlDraft2LanguageFactory(ConfigFactory.empty()))

    languageFactory
  }

  /**
    * Read the given file into a string
    * @param filePath absolute path to file
    * @return Content of file as a string
    */
  def readFile(filePath: String): String = Try(Files.readAllLines(Paths.get(filePath)).asScala.mkString(System.lineSeparator())).get

  /**
    * Get the the first non comment line in the file
    * @param descriptorFilePath path to the file to read
    * @return Optional string containing the first line of code in the file
    */
  def getFirstCodeLine(descriptorFilePath: String): Optional[String] = {
    val commentIndicators = List("#")
    val content = readFile(descriptorFilePath)
    val fileWithoutInitialWhitespace = content.linesIterator.toList.dropWhile { l =>
      l.forall(_.isWhitespace) || commentIndicators.exists(l.dropWhile(_.isWhitespace).startsWith(_))
    }

    val firstCodeLine = fileWithoutInitialWhitespace.headOption.map(_.dropWhile(_.isWhitespace))
    Optional.ofNullable(firstCodeLine.orNull)
  }
}

object WdlBridge {
  val logger = LoggerFactory.getLogger(WdlBridge.getClass)
}

/**
  * Class for resolving imports defined in memory (mapping of path to content)
  */
case class MapResolver(filePath: String) extends ImportResolver {
  var secondaryWdlFiles = new util.HashMap[String, String]()

  def setSecondaryFiles(secondaryFiles: util.HashMap[String, String]): Unit = {
    secondaryWdlFiles = secondaryFiles
  }

  override def name: String = "Map importer"

  override protected def innerResolver(path: String, currentResolvers: List[ImportResolver]): Checked[ImportResolver.ResolvedImportBundle] = {
    val importPath = path.replaceFirst("file://", "")
    val absolutePath = LanguageHandlerHelper.convertRelativePathToAbsolutePath(this.filePath, importPath)
    val content = secondaryWdlFiles.get(absolutePath)
    val mapResolver = MapResolver(absolutePath)
    mapResolver.setSecondaryFiles(this.secondaryWdlFiles)
    if (content == null) InvalidCheck(s"Not found $path for resolver with path $this.filePath").invalidNelCheck else ResolvedImportBundle(content, List(mapResolver), ResolvedImportRecord(absolutePath.toString)).validNelCheck
  }

  override def cleanupIfNecessary(): ErrorOr[Unit] = ().validNel

  override def hashKey: ErrorOr[String] = ().hashCode().toString.validNel
}
case class DockerParameter(imageName: String, imageReference: DockerImageReference)

object WdlBridgeShutDown {
  def shutdownSTTP(): Unit = {
    HttpResolver.closeBackendIfNecessary();
    WdlBridge.logger.info("WDL HTTP import resolver closed")
  }
}

