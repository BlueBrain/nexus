package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{Realm, WellKnown}
import ch.epfl.bluebrain.nexus.delta.sdk.model._

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

  def resourceFor(
      realm: Realm,
      rev: Long,
      subject: Subject,
      deprecated: Boolean = false
  )(implicit base: BaseUri): RealmResource = {
    val accessUrl = AccessUrl.realm(realm.label)
    ResourceF(
      id = accessUrl.iri,
      accessUrl = accessUrl,
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

}
