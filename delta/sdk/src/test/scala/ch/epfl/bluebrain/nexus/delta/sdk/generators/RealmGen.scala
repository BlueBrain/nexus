package ch.epfl.bluebrain.nexus.delta.sdk.generators

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.{Realm, RealmState, WellKnown}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import org.http4s.Uri

import java.time.Instant

object RealmGen {

  def state(
      openIdConfig: Uri,
      wk: WellKnown,
      rev: Int,
      deprecated: Boolean = false,
      logo: Option[Uri] = None,
      acceptedAudiences: Option[NonEmptySet[String]] = None,
      subject: Subject = Anonymous
  ): RealmState =
    RealmState(
      Label.unsafe(wk.issuer),
      rev,
      deprecated = deprecated,
      Name.unsafe(s"${wk.issuer}-name"),
      openIdConfig,
      wk.issuer,
      wk.keys,
      wk.grantTypes,
      logo,
      acceptedAudiences,
      wk.authorizationEndpoint,
      wk.tokenEndpoint,
      wk.userInfoEndpoint,
      wk.revocationEndpoint,
      wk.endSessionEndpoint,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    )

  def realm(
      openIdConfig: Uri,
      wk: WellKnown,
      logo: Option[Uri] = None,
      acceptedAudiences: Option[NonEmptySet[String]] = None
  ): Realm =
    model.Realm(
      Label.unsafe(wk.issuer),
      Name.unsafe(s"${wk.issuer}-name"),
      openIdConfig,
      wk.issuer,
      wk.grantTypes,
      logo,
      acceptedAudiences,
      wk.authorizationEndpoint,
      wk.tokenEndpoint,
      wk.userInfoEndpoint,
      wk.revocationEndpoint,
      wk.endSessionEndpoint,
      wk.keys
    )

  def resourceFor(
      realm: Realm,
      rev: Int,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  ): RealmResource = {
    val wk = model.WellKnown(
      realm.issuer,
      realm.grantTypes,
      realm.keys,
      realm.authorizationEndpoint,
      realm.tokenEndpoint,
      realm.userInfoEndpoint,
      realm.revocationEndpoint,
      realm.endSessionEndpoint
    )
    state(realm.openIdConfig, wk, rev, deprecated, realm.logo, realm.acceptedAudiences, subject)
      .copy(label = realm.label, name = realm.name)
      .toResource
  }

}
