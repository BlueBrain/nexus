import sbt.{VersionNumber, VersionNumberCompatibility}

/**
  * Custom java compatibility check.  Any higher version than current is considered compatible.
  */
object CompatibleJavaVersion extends VersionNumberCompatibility {
  override val name = "Java specification compatibility"

  override def isCompatible(current: VersionNumber, required: VersionNumber): Boolean =
    current.numbers
      .zip(required.numbers)
      .foldRight(required.numbers.size <= current.numbers.size) { case ((curr, req), acc) =>
        (curr > req) || (curr == req && acc)
      }

  def apply(current: VersionNumber, required: VersionNumber): Boolean =
    isCompatible(current, required)
}
