import java.io.File

import sbt._

object WaitForIt {

  def download(target: File): File = {
    import scala.sys.process._
    val waitUrl = url("https://raw.githubusercontent.com/vishnubob/wait-for-it/master/wait-for-it.sh")
    val file    = target / "wait-for-it.sh"
    assert((waitUrl #> file !) == 0, "Downloading wait-for-it.sh script failed")
    file
  }
}
