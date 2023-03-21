package ch.epfl.bluebrain.nexus.delta.kernel.utils

import java.io.{PrintWriter, StringWriter}

trait ThrowableUtils {

  /**
    * Outputs readable stack trace for the given throwable.
    */
  def stackTraceAsString(t: Throwable): String = {
    val sw = new StringWriter
    t.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

}

object ThrowableUtils extends ThrowableUtils
