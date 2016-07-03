package io.glassdoor.plugin.plugins.analyser.grep

import java.io.{BufferedWriter, File, FileWriter}

import io.glassdoor.application._
import io.glassdoor.plugin.{DynamicValues, Plugin}

import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source

/**
  * Runs grep with the specified regex or input files over the files inside the specified location.
  * Stores the matching lines in a log file in the specified destination.
  * Created by Florian Schrofner on 3/30/16.
  */
class GrepAnalyser extends Plugin{

  var mResult:Option[Map[String,String]] = None
  var mAdditionalParameters = new StringBuilder
  var mPatternMatcher = PatternMatcher.Extended
  var mPrintHeaders = false


  /**
    * This method should only be overridden, when specifying either dynamic dependencies or dynamic changes in the manifest.
    * This method will then be called with the given parameters, before the plugin can be scheduled.
    * The result should contain the values requested. Specify None, if you did not specify this value as dynamic.
    * None values will be ignored, to change your dynamic dependency to an empty dependency wrap an empty string array in Some = Some(Array[String]()).
    */
  override def resolveDynamicValues(parameters: Array[String]): DynamicValues = {
    if(parameters.length == 3){
      return DynamicValues(uniqueId, Some(Array[String](parameters(1))), Some(Array[String](parameters(2))))
    } else {
      val parameterArray = CommandInterpreter.parseToParameterArray(parameters)

      var source:Option[String] = None
      var destination:Option[String] = None
      val dependencyBuffer = ArrayBuffer[String]()

      if(parameterArray.isDefined) {
        for(parameter <- parameterArray.get){
          if(parameter.paramType == ParameterType.Parameter){
            if(source.isEmpty){
              source = Some(parameter.name)
            } else if(destination.isEmpty){
              destination = Some(parameter.name)
            }
          }
          if(parameter.paramType == ParameterType.NamedParameter){
            if(parameter.name == "input" | parameter.name == "i"){
              if(parameter.value.isDefined){
                dependencyBuffer.append(parameter.value.get)
              }
            }
          }
        }
      }

      var changes:Option[Array[String]] = None

      if(source.isDefined){
        dependencyBuffer.append(source.get)
      }

      val dependencies = Some(dependencyBuffer.toArray)

      if(destination.isDefined){
        changes = Some(Array[String](destination.get))
      }

      DynamicValues(uniqueId, dependencies, changes)
    }
  }

  override def apply(data:Map[String,String], parameters: Array[String]): Unit = {
    try {
      if(parameters.length == 3){
        Log.debug("no additional parameters, calling grep")

        //shortest possible call
        val regex = parameters(0)
        val src = parameters(1)
        val dest = parameters(2)
        callGrepWithRegex(regex,src,dest, data)
      } else {
        Log.debug("additional parameters specified, need to parse..")

        //convert to parameter keymap and iterate
        val parameterArray = CommandInterpreter.parseToParameterArray(parameters)

        if(parameterArray.isDefined){
          Log.debug("successfully parsed parameter array, size: " + parameterArray.get.length)

          var inputOpt:Option[Array[String]] = None
          var inputSubFilesOpt:Option[mutable.MultiMap[String,String]] = None

          val inputFiles = mutable.ArrayBuffer.empty[String]
          val inputSubFiles = new collection.mutable.HashMap[String, collection.mutable.Set[String]]() with collection.mutable.MultiMap[String, String]

          var srcOpt:Option[String] = None
          var destOpt:Option[String] = None

          for(parameter <- parameterArray.get){
            if(parameter.paramType == ParameterType.NamedParameter){
              parameter.name match {
                case "input" | "i" =>
                  Log.debug("found input tag")
                  if(parameter.value.isDefined){
                    inputFiles += parameter.value.get
                  }
                case "subfile" | "s" =>
                  Log.debug("found subfile tag")
                  if(parameter.value.isDefined){
                    inputSubFiles.addBinding(inputFiles.last, parameter.value.get)
                  }
                case _ =>
                  Log.debug("error: unrecognised parameter!")
              }
            } else if(parameter.paramType == ParameterType.Flag){
              parameter.name match {
                case "only-matching" | "o" =>
                  mAdditionalParameters.append("o")
                case "no-filename" | "h" =>
                  mAdditionalParameters.append("h")
                case "line-number" | "n" =>
                  mAdditionalParameters.append("n")
                case "ignore-case" | "i" =>
                  mAdditionalParameters.append("i")
                case "print-headers" | "j" =>
                  mPrintHeaders = true
                case "fixed-strings" | "F" =>
                  mPatternMatcher = PatternMatcher.Strings
                case "basic-regexp" | "G" =>
                  mPatternMatcher = PatternMatcher.Basic
                case "perl-regexp" | "P" =>
                  mPatternMatcher = PatternMatcher.Perl
                case _ =>
                  Log.debug("error: unrecognised flag!")
              }
            } else if(parameter.paramType == ParameterType.Parameter){
              //save src at first and dest at second occurrence
              if(srcOpt.isEmpty){
                srcOpt = Some(parameter.name)
              } else if(destOpt.isEmpty){
                destOpt = Some(parameter.name)
              } else {
                Log.debug("error: trailing parameter")
              }
            }

          }

          if(inputFiles.length > 0){
            Log.debug("input files were specified")
            inputOpt = Some(inputFiles.toArray)
          }

          if(inputSubFiles.size > 0) {
            inputSubFilesOpt = Some(inputSubFiles)
          }

          if(srcOpt.isDefined && destOpt.isDefined && inputOpt.isDefined){
            callGrepWithInputArray(inputOpt.get,inputSubFilesOpt,srcOpt.get,destOpt.get,data)
          } else {
            Log.debug("error: either src, dest or input not defined!")
          }
        } else {
          Log.debug("parameters could not be parsed!")
        }
      }
    } catch {
      case e: ArrayIndexOutOfBoundsException =>
        Log.debug("error: array index out of bounds")
        mResult = None
      case e: Exception =>
        Log.debug("error: other exception")
        e.printStackTrace()
    } finally {
      ready
    }

  }

  def getPatternMatcherString():String = {
    mPatternMatcher match {
      case PatternMatcher.Basic =>
        "G"
      case PatternMatcher.Perl =>
        "P"
      case PatternMatcher.Strings =>
        "F"
      case _ =>
        "E"
    }
  }

  def callGrepWithRegex(regex:String, src:String, dest:String, data:Map[String,String]): Unit ={
    Log.debug("calling grep with regex..")

    val srcPath = data.get(src)
    val workingDirectory = data.get(ContextConstant.FullKey.ConfigWorkingDirectory)

    if(srcPath.isDefined && workingDirectory.isDefined){
      val destPath = workingDirectory.get + "/" + ContextConstant.Key.Grep + "/" + splitDescriptor(dest)(1) + "/result.log"
      val outputFile = new File(destPath)
      outputFile.getParentFile.mkdirs()

      val command = "grep -ar" + getPatternMatcherString() + mAdditionalParameters.toString() + " \"" + regex + "\" " + srcPath.get

      Log.debug("issuing command: " + command)

      val executor = new SystemCommandExecutor
      val commandResult = executor.executeSystemCommand(command)

      if(commandResult.isDefined){
        val output = commandResult.get

        //write the resulting log
        val bw = new BufferedWriter(new FileWriter(outputFile, true))

        if(mPrintHeaders){
          bw.write(GrepAnalyserConstant.HeaderLine.format(regex, src))
          bw.write(GrepAnalyserConstant.NewLine)
        }

        bw.write(output)

        if(mPrintHeaders){
          bw.write(GrepAnalyserConstant.NewLine)
        }

        bw.close()

        Log.debug("grep finished, saved log to: " + outputFile.getAbsolutePath)

        //only save parent directory, not the exact file
        val result = HashMap[String,String](dest -> outputFile.getParent)
        mResult = Some(result)
      } else {
        //result code of 1 just means that no lines were selected (see grep documentation)
        Log.debug("error: when issuing grep (result code of 1 is fine)")
        val resultCode = executor.getResultCode
        val error = executor.getErrorOutput
        if(resultCode.isDefined){
          Log.debug("result code: " + resultCode.get)
        } else {
          Log.debug("no result code")
        }
        if(error.isDefined){
          Log.debug("error output: " + error.get)
        } else {
          Log.debug("no error output")
        }
      }
    } else {
      Log.debug("error: either src path or working directory are not defined!")
    }
  }

  def callGrepWithInputFile(inputFilePath:String, src:String, dest:String, data:Map[String,String]):Unit = {
    Log.debug("calling grep with input file: " + inputFilePath)
    val inputFile = new File(inputFilePath)

    if(inputFile.exists() && inputFile.isDirectory){
      //recursively call method
      val subFiles = inputFile.list()

      if(subFiles.length > 0){
        for(file <- subFiles){
          callGrepWithInputFile(file, src, dest, data)
        }
      } else {
        Log.debug("directory " + inputFilePath + " is empty!")
      }
    } else if(inputFile.exists()){
      for (line <- Source.fromFile(inputFile).getLines()) {
        callGrepWithRegex(line, src, dest, data)
      }
    } else {
      Log.debug("error: input file not found!")
    }
  }

  def callGrepWithInputArray(inputs:Array[String], inputSubFiles:Option[mutable.MultiMap[String,String]], src:String, dest:String, data:Map[String,String]):Unit = {
    Log.debug("calling grep with input array..")

    for(input <- inputs){
      val path = data.get(input)

      if(path.isDefined){
        if(inputSubFiles.isDefined && inputSubFiles.get.contains(input)){
          Log.debug("subfiles were specified for: " + input)
          //get all folders that were specified for that input
          val subFilePaths = inputSubFiles.get.get(input).get //this is crazy

          for(subFile <- subFilePaths){
            val inputFilePath = path.get + File.separator + subFile
            //TODO: maybe split destinations up to differentiate inputs?
            callGrepWithInputFile(inputFilePath, src, dest, data)
          }

        } else {
          Log.debug("no subfiles specified for: " + input)
          callGrepWithInputFile(path.get, src, dest, data)
        }
      } else {
        Log.debug("input path is not defined in data")
      }
    }
  }

  def splitDescriptor(descriptor:String):Array[String] = {
    descriptor.split(Constant.Regex.DescriptorSplitRegex)
  }

  override def result: Option[Map[String,String]] = {
    mResult
  }

  override def help(parameters: Array[String]): Unit = ???
}

object PatternMatcher extends Enumeration {
  type PatternMatcher= Value
  val  Extended, Strings, Basic, Perl = Value
}

object GrepAnalyserConstant {
  val NewLine = sys.props("line.separator")
  val HeaderPrefix = "####################"
  val HeaderPostfix = "####################"
  val HeaderLine = HeaderPrefix + " %s at %s " + HeaderPostfix + NewLine
}