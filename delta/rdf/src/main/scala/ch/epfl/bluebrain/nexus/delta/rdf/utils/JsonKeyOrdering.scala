package ch.epfl.bluebrain.nexus.delta.rdf.utils

/**
  * Ordering for Json keys.
  */
trait JsonKeyOrdering extends Ordering[String]

object JsonKeyOrdering {

  val alphabetical = apply(Seq.empty, Seq.empty)

  /**
    * Ordering based on passed keys sequences. Any json key will be sorted as the order on the ''topKeys'' plus ''bottomKeys''.
    * If the keys to order do not exist in the passed ''topKeys'' or ''bottomKeys'' sequence, they will be positioned
    * in between those two ranges of keys using the ''middleKeysOrdering''.
    *
    * @param topKeys            the Json keys ordering sequence
    * @param bottomKeys         the bottom Json keys ordering sequence
    * @param middleKeysOrdering the ordering to apply to keys that are not present in the ''topKeys'' nor the ''bottomKeys''
    */
  final def apply(
      topKeys: Seq[String],
      bottomKeys: Seq[String],
      middleKeysOrdering: Ordering[String] = Ordering.String
  ): JsonKeyOrdering =
    new JsonKeyOrdering {
      private val keysMap: Map[String, Int]  = ((topKeys :+ "*") ++ bottomKeys).zipWithIndex.toMap
      private val middlePosition             = keysMap("*")
      private def position(key: String): Int = keysMap.getOrElse(key, middlePosition)

      override def compare(x: String, y: String): Int = {
        (position(x), position(y)) match {
          case (`middlePosition`, `middlePosition`) => middleKeysOrdering.compare(x, y)
          case (xPos, yPos)                         => xPos compare yPos
        }

      }
    }
}
