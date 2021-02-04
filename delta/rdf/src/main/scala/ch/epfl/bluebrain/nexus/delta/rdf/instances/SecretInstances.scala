package ch.epfl.bluebrain.nexus.delta.rdf.instances

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder

trait SecretInstances {
  implicit def secretJsonLdDecoder[A](implicit D: JsonLdDecoder[A]): JsonLdDecoder[Secret[A]] =
    D.map(Secret.apply)
}
