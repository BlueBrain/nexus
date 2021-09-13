import sbt._
import sbt.io.Using

import java.io.File

object WaitForIt {

  def download(target: File): File = {
    val waitUrl = url("https://raw.githubusercontent.com/vishnubob/wait-for-it/master/wait-for-it.sh")
    val file    = target / "wait-for-it.sh"
    Using.urlInputStream(waitUrl) { is =>
      IO.transfer(is, file)
    }
    file
  }
}
