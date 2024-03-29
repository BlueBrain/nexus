package ch.epfl.bluebrain.nexus.storage

import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

import scala.sys.process._

class StringProcessLoggerSpec extends BaseSpec {
  "A StringProcessLogger" should {
    "log stdout" in {
      val cmd      = List("echo", "-n", "Hello", "world!")
      val process  = Process(cmd)
      val logger   = StringProcessLogger(cmd)
      val exitCode = process ! logger
      exitCode shouldEqual 0
      logger.toString shouldEqual "Hello world!"
    }

    "log stderr" in {
      val cmd      = List("cat", "/")
      val process  = Process(cmd)
      val logger   = StringProcessLogger(cmd)
      val exitCode = process ! logger
      exitCode should not be 0
      logger.toString shouldEqual "cat: /: Is a directory"
    }
  }
}
