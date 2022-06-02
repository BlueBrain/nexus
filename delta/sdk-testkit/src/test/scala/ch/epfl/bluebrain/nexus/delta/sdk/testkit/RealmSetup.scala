package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.generators.WellKnownGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import monix.bio.{IO, UIO}

object RealmSetup {

  implicit val serviceAccount: User = User("service", Label.unsafe("internal"))

  /**
    * Set up an create Realms for the given labels
    */
  def init(realmLabels: Label*): UIO[Realms] =
    for {
      realms <- RealmsDummy(uri => IO.pure(WellKnownGen.createFromUri(uri, "issuer")))
      _      <- realmLabels.toList
                  .traverse { label =>
                    realms
                      .fetch(label)
                      .onErrorFallbackTo {
                        realms.create(label, Name.unsafe(label.value), s"http://localhost/$label/", None, None)
                      }
                      .void
                  }
                  .hideErrorsWith(_ => new IllegalStateException())
    } yield realms

}
