package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{Realm, WellKnown}

object RealmGen {

  def currentState(
      openIdConfig: Uri,
      wk: WellKnown,
      rev: Long,
      deprecated: Boolean = false,
      logo: Option[Uri] = None
  ): Current =
    Current(
      Label.unsafe(wk.issuer),
      rev,
      deprecated = deprecated,
      Name.unsafe(s"${wk.issuer}-name"),
      openIdConfig,
      wk.issuer,
      wk.keys,
      wk.grantTypes,
      logo,
      wk.authorizationEndpoint,
      wk.tokenEndpoint,
      wk.userInfoEndpoint,
      wk.revocationEndpoint,
      wk.endSessionEndpoint,
      Instant.EPOCH,
      Anonymous,
      Instant.EPOCH,
      Anonymous
    )

  def realm(openIdConfig: Uri, wk: WellKnown, logo: Option[Uri] = None): Realm =
    Realm(
      Label.unsafe(wk.issuer),
      Name.unsafe(s"${wk.issuer}-name"),
      openIdConfig,
      wk.issuer,
      wk.grantTypes,
      logo,
      wk.authorizationEndpoint,
      wk.tokenEndpoint,
      wk.userInfoEndpoint,
      wk.revocationEndpoint,
      wk.endSessionEndpoint,
      wk.keys
    )

  def resourceFor(realm: Realm, rev: Long, subject: Subject, deprecated: Boolean = false): ResourceF[Label, Realm] =
    ResourceF(
      id = Label.unsafe(realm.issuer),
      rev = rev,
      types = Set(nxv.Realm),
      deprecated = deprecated,
      createdAt = Instant.EPOCH,
      createdBy = subject,
      updatedAt = Instant.EPOCH,
      updatedBy = subject,
      schema = Latest(schemas.realms),
      value = realm
    )

}
