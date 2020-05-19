package ch.epfl.bluebrain.nexus

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.permissions.Permission
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.Aggregate

package object realms {

  /* Type annotations specific to realms */
  type Event     = RealmEvent
  type State     = RealmState
  type Command   = RealmCommand
  type Rejection = RealmRejection
  type Agg[F[_]] = Aggregate[F, String, Event, State, Command, Rejection]

  type ResourceMetadata = ResourceF[(RealmLabel, Boolean)]
  type EventOrRejection = Either[Rejection, Event]
  type MetaOrRejection  = Either[Rejection, ResourceMetadata]

  type Resource    = ResourceF[Either[DeprecatedRealm, ActiveRealm]]
  type OptResource = Option[Resource]

  type RealmIndex[F[_]] = KeyValueStore[F, RealmLabel, Resource]

  /**
    * The constant collection of realm types.
    */
  final val types: Set[Uri] = Set(nxv.Realm)

  /* Constant permissions */
  final val read  = Permission.unsafe("realms/read")
  final val write = Permission.unsafe("realms/write")

}
