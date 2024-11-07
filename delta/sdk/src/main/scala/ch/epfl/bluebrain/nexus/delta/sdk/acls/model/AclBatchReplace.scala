package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import io.circe.Decoder

final case class AclBatchReplace(acls: Vector[Acl])

object AclBatchReplace {

  implicit val aclBatchReplaceDecoder: Decoder[AclBatchReplace] = Decoder.instance { hc =>
    hc.get[Map[AclAddress, AclValues]]("acls").map { valuesMap =>
      val acls = valuesMap.foldLeft(Vector.empty[Acl]) { case (acc, (address, values)) =>
        acc :+ Acl(address, values.value: _*)
      }
      AclBatchReplace(acls)
    }
  }

}
