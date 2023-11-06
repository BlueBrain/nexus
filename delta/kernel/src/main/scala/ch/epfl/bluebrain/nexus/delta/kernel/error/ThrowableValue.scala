package ch.epfl.bluebrain.nexus.delta.kernel.error

abstract class ThrowableValue extends Throwable { self =>
  override def fillInStackTrace(): Throwable = self
}
