package ch.epfl.bluebrain.nexus.util

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import scala.annotation.tailrec
import scala.util.Random

/**
  * Utility trait that facilitates generating random test data.
  */
trait Randomness {

  /**
    * Generates an arbitrary string.
    *
    * @param length the length of the string to be generated
    * @param pool   the possible values that the string may contain
    * @return a new arbitrary string of the specified ''length'' from the specified pool of ''characters''
    */
  final def genString(length: Int = 16, pool: IndexedSeq[Char] = Vector.range('a', 'z')): String = {
    val size = pool.size

    @tailrec
    def inner(acc: String, remaining: Int): String =
      if (remaining <= 0) acc
      else inner(acc + pool(Random.nextInt(size)), remaining - 1)

    inner("", length)
  }

  /**
    * Generates a positive integer with the maximum value defined by the argument ''max''.
    *
    * @param max the maximum integer value (inclusive)
    * @return a new random integer in the [0, max] interval
    */
  final def genInt(max: Int = 100): Int =
    Random.nextInt(max)

  /**
    * @return a random available port on the loopback interface
    */
  final def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val port         = serverSocket.getLocalPort
    serverSocket.close()
    port
  }
}

object Randomness extends Randomness
