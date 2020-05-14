package ch.epfl.bluebrain.nexus.sourcing.akka

import akka.actor.NotInfluenceReceiveTimeout
import akka.routing.ConsistentHashingRouter.ConsistentHashable

private[akka] trait Msg extends ConsistentHashable with Product with Serializable {

  /**
    * @return the persistence id
    */
  def id: String

  override def consistentHashKey: String = id
}

private[akka] object Msg {

  /**
    * Message for replying when an incorrect message is sent to the actor.
    *
    * @param id the id
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  final case class UnexpectedMsgId(id: String, receivedMsgId: String)
      extends Exception
      with Msg
      with NotInfluenceReceiveTimeout {
    override def fillInStackTrace(): Throwable = this
  }

  /**
    * Message for replying when an incorrect message value type is sent to the actor.
    *
    * @param id the persistence id
    * @param expected the expected message value type
    * @param received the received message value
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  final case class TypeError(id: String, expected: String, received: Any)
      extends Exception
      with Msg
      with NotInfluenceReceiveTimeout {
    override def fillInStackTrace(): Throwable = this
  }

  /**
    * Message to signal that a command evaluation has timed out.
    *
    * @param id       the persistence id
    * @param command  the command that timed out
    * @tparam Command the type of the command
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  final case class CommandEvaluationTimeout[Command](id: String, command: Command)
      extends Exception
      with Msg
      with NotInfluenceReceiveTimeout {
    override def fillInStackTrace(): Throwable = this
  }

  /**
    * Message to signal that a command evaluation has returned in an error.
    *
    * @param id       the persistence id
    * @param command  the command that timed out
    * @param message  an optional message describing the cause of the error
    * @tparam Command the type of the command
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  final case class CommandEvaluationError[Command](id: String, command: Command, message: Option[String])
      extends Exception
      with Msg
      with NotInfluenceReceiveTimeout {
    override def fillInStackTrace(): Throwable = this
  }

  /**
    * Message to signal that the actor should stop.
    *
    * @param id the persistence id
    */
  final case class Done(id: String) extends Msg

}
