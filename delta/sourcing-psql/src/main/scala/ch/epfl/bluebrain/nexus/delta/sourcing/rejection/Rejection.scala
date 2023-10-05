package ch.epfl.bluebrain.nexus.delta.sourcing.rejection

/**
  * Parent type for rejections
  */
abstract class Rejection extends Exception with Product with Serializable { self =>

  override def fillInStackTrace(): Throwable = self

  override def getMessage: String = reason

  def reason: String

}
