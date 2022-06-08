package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset

object SSEUtils {

  /**
    * Take a couple of id and class, extracts the name of the class and generate the sequence according to the couple
    * position
    * @param values
    *   the values to transform
    */
  def list[Id, Tpe](values: (Id, Tpe)*): Seq[(Id, String, Sequence)] =
    values.zipWithIndex.map { case ((id, tpe), index) =>
      (id, ClassUtils.simpleName(tpe), Sequence(index.toLong + 1))
    }

  /**
    * Take a couple of id and class, extracts the name of the class and generate the sequence according to the couple
    * position
    * @param values
    *   the values to transform
    */
  def extract[Id, Tpe](values: (Id, Tpe)*): Seq[(Id, String, Offset)] =
    values.zipWithIndex.map { case ((id, tpe), index) =>
      (id, ClassUtils.simpleName(tpe), Offset.at(index.toLong + 1))
    }

}
