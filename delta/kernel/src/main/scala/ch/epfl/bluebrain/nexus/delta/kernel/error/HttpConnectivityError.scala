package ch.epfl.bluebrain.nexus.delta.kernel.error

import java.net.ConnectException
import scala.concurrent.TimeoutException

object HttpConnectivityError {

  @SuppressWarnings(Array("IsInstanceOf"))
  def test: Throwable => Boolean = {
    case _: ConnectException => true
    case _: TimeoutException => true
    case _: Throwable        => false
  }
}
