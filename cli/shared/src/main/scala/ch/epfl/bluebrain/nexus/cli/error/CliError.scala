package ch.epfl.bluebrain.nexus.cli.error

import cats.Show

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
trait CliError extends Exception {
  override def fillInStackTrace(): CliError = this
  def reason: String
  def lines: List[String]
  def footer: Option[String] = None
}

object CliError {
  implicit val cliErrorShow: Show[CliError] = Show.show { err =>
    s"""🔥  An error occurred because '${Console.RED}${err.reason}${Console.RESET}', details:
       |🔥
       |${err.lines.map(l => s"🔥    $l").mkString("\n")}${err.footer
         .map(l => s"\n🔥  $l")
         .mkString("\n")}""".stripMargin
  }
}
