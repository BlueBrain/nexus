package ch.epfl.bluebrain.nexus.cli.error

import cats.Show

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
trait CliError extends Exception {
  override def fillInStackTrace(): CliError = this
  override def getMessage: String           = s"Reason: '$reason'"

  def reason: String
  def lines: List[String]
  def footer: Option[String] = None

  def asString: String =
    s"""🔥  An error occurred because '${Console.RED}$reason${Console.RESET}', details:
       |🔥
       |${lines.map(l => s"🔥    $l").mkString("\n")}
       |${footer.map(l => s"\n🔥  $l").mkString("\n")}""".stripMargin
}

object CliError {
  implicit final val cliErrorShow: Show[CliError] = Show.show(_.asString)
}
