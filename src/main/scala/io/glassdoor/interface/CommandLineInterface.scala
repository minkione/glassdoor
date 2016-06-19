package io.glassdoor.interface

import java.io.PrintWriter
import java.util.concurrent.TimeUnit
import javax.smartcardio.TerminalFactory

import akka.actor.{Cancellable, ActorRef, Props}
import io.glassdoor.application.{Log, Context, CommandInterpreter, Command}
import io.glassdoor.bus.{Message, MessageEvent, EventBus}
import io.glassdoor.controller.ControllerConstant
import io.glassdoor.plugin.PluginInstance
import io.glassdoor.plugin.manager.PluginManagerConstant.PluginErrorCodes
import jline.{UnixTerminal, Terminal}
import jline.console.ConsoleReader
import jline.console.completer.StringsCompleter
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.duration.Duration

/**
  * Created by Florian Schrofner on 3/15/16.
  */
class CommandLineInterface extends UserInterface {

  var mConsole:Option[ConsoleReader] = None
  var mCompleter:Option[StringsCompleter] = None
  var mCommandLineReader:Option[ActorRef] = None
  var mCounter = 0

  //TODO: there can be mutliple animations going on!
  var mAnimationTask:Option[Cancellable] = None


  override def receive: PartialFunction[Any, Unit] = {
    //let the default user interface trait handle all messages,
    //except commandline specific ones
    super.receive orElse {
      case CommandLineMessage(action, data) =>
        action match {
          case CommandLineInterfaceConstant.Action.handleLine =>
            if(data.isDefined){
              val line = data.get.asInstanceOf[String]
              handleLine(line)
            }
        }
    }
  }

  override def initialise(context: Context): Unit = {
    Log.debug("initialising interface..")

    val console = new ConsoleReader()

    console.getTerminal.init()
    console.clearScreen()
    console.setPrompt(">")

    mConsole = Some(console)

    startReadingFromCommandline()

//    var line:String = null
//    setupAutoComplete()

    //loop forever until exit command is called
    //TODO: write man + store default commands centrally
    //TODO: this loop blocks akka from receiving more messages

//    while({line = console.readLine();line} != "exit"){
//      handleLine(line)
//      println("continue with loop!")
//    }

    //terminate()
  }

  def startReadingFromCommandline():Unit = {
    //run reader as own thread in order to prevent blocking of interface changes
    val commandLineReader = context.system.actorOf(Props(new CommandLineReader(self)))
    mCommandLineReader = Some(commandLineReader)

    commandLineReader ! CommandLineMessage(CommandLineReaderConstant.Action.init, mConsole)
  }

  def handleLine(line:String):Unit = {
    Log.debug("handle line called!")
    Log.debug("line: " + line)
    //TODO: handle "exit"!
    val input = CommandInterpreter.interpret(line)

    if(input.isDefined){
      //don't show next command prompt, while there is still a task executing
      mConsole.get.resetPromptLine("","",0)

      //TODO: use list of system commands instead
      if(input.get.name == "install"){
        Log.debug("install called!")
        EventBus.publish(MessageEvent(ControllerConstant.channel, Message(ControllerConstant.Action.installResource, Some(input.get.parameters))))
      } else if(input.get.name == "exit"){
        Log.debug("exit called!")
        if(mConsole.isDefined){
          mConsole.get.shutdown()
          mConsole = None
        }
        terminate()
      } else {
        EventBus.publish(MessageEvent(ControllerConstant.channel, Message(ControllerConstant.Action.applyPlugin, input)))
      }
    }

  }

//  def setupAutoComplete():Unit = {
//    //TODO: handover all possible commands (system commands + plugins + aliases)
//    val completer = new StringsCompleter()
//    mCompleter = Some(completer)
//  }

  override def showPluginList(plugins: Array[PluginInstance]): Unit = {
    if(mConsole.isDefined){
      val console = mConsole.get
      for(plugin:PluginInstance <- plugins){
        console.println(plugin.kind + ":" + plugin.name)
      }
    }
  }

  override def showProgress(taskId: Long, progress: Float): Unit = ???

  override def showEndlessProgress(taskId: Long): Unit = {
    Log.debug("commandline interface: showing endless progress")
    if(mConsole.isDefined){
      Log.debug("console defined")
      //val console = mConsole.get
      val handle = context.system.scheduler.schedule(Duration.Zero, Duration.create(1, TimeUnit.SECONDS))(updateEndlessProgress(taskId))
      mAnimationTask = Some(handle)
    } else {
      Log.debug("error: console not defined!")
    }
  }

  def updateEndlessProgress(taskId: Long):Unit = {
    //TODO: check isDefined
    val console = mConsole.get
    val stringBuilder = new StringBuilder()
    stringBuilder.append(taskId + ": ")
    stringBuilder.append(CommandLineInterfaceConstant.Progress.startString)

    for(i <- 1 to CommandLineInterfaceConstant.Progress.progressbarLength){
      if((i > mCounter && i <= mCounter + CommandLineInterfaceConstant.Progress.endlessProgressLength)
      || (i < (mCounter + CommandLineInterfaceConstant.Progress.endlessProgressLength) - CommandLineInterfaceConstant.Progress.progressbarLength)){
        stringBuilder.append(CommandLineInterfaceConstant.Progress.progressbarFilledString)
      } else {
        stringBuilder.append(CommandLineInterfaceConstant.Progress.progressbarEmptyString)
      }
    }

    stringBuilder.append(CommandLineInterfaceConstant.Progress.endString)

    console.resetPromptLine("",stringBuilder.toString(),-1)

    mCounter += 1

    if(mCounter >= CommandLineInterfaceConstant.Progress.progressbarLength){
      mCounter = 0
    }
  }

  override def taskCompleted(taskId: Long): Unit = {
    //TODO: only if no more tasks are executing
    //TODO: can't handle this
    Log.debug("interface received task completed")

    stopAnimation(taskId)

    //show completed task
    val stringBuilder = new StringBuilder()
    stringBuilder.append(taskId + ": ")
    stringBuilder.append(CommandLineInterfaceConstant.Progress.startString)

    for(i <- 1 to CommandLineInterfaceConstant.Progress.progressbarLength){
      stringBuilder.append(CommandLineInterfaceConstant.Progress.progressbarFilledString)
    }

    stringBuilder.append(CommandLineInterfaceConstant.Progress.endString)

    if(mConsole.isDefined){
      val console = mConsole.get
      console.resetPromptLine("",stringBuilder.toString(),-1)
      console.println()
    }

    //wait for new commands
    if(mCommandLineReader.isDefined){
      val commandLineReader = mCommandLineReader.get
      commandLineReader ! CommandLineMessage(CommandLineReaderConstant.Action.read, None)
    }
  }

  def stopAnimation(taskId: Long):Unit = {
    //TODO: stop correct animation
    if(mAnimationTask.isDefined){
      mAnimationTask.get.cancel()
      mAnimationTask = None
    }
  }

  override def taskFailed(taskId: Long, error: Int, data:Option[Any]): Unit = {
    stopAnimation(taskId)

    if(mConsole.isDefined){
      //TODO: print matching error

      error match {
        case PluginErrorCodes.dependenciesNotSatisfied =>
          if(data.isDefined){
            mConsole.get.println("error: dependency not satisfied: " + data.get.asInstanceOf[String])
          }
        case PluginErrorCodes.dependenciesInChange =>
          if(data.isDefined){
            mConsole.get.println("error: dependency in change: " + data.get.asInstanceOf[String])
          }
        case PluginErrorCodes.pluginNotFound =>
          mConsole.get.println("error: plugin not found!")
      }
    }

    if(mCommandLineReader.isDefined){
      val commandLineReader = mCommandLineReader.get
      commandLineReader ! CommandLineMessage(CommandLineReaderConstant.Action.read, None)
    }
  }
}

case class CommandLineMessage(action: String, data:Option[Any])

object CommandLineInterfaceConstant {
  object Action {
    val handleLine = "handleLine"
  }
  object Progress{
    val startString = "["
    val endString = "]"
    val progressbarLength = 25
    val progressbarEmptyString = " "
    val progressbarFilledString = "#"
    val endlessProgressLength = 20
  }
}