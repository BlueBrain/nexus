package ch.epfl.bluebrain.nexus.delta.kernel.error

import akka.stream.StreamTcpException

import java.net.{ConnectException, UnknownHostException}
import scala.concurrent.TimeoutException

object HttpConnectivityError {
  def test: Throwable => Boolean = {
    case _: ConnectException                                                    => true
    case _: TimeoutException                                                    => true
    case e: StreamTcpException if e.getCause.isInstanceOf[UnknownHostException] => true
    case _: Throwable                                                           => false
  }
}
