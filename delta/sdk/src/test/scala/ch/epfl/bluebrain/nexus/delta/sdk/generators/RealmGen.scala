package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{Realm, WellKnown}
import org.scalatest.OptionValues

object RealmGen extends OptionValues {

  def currentState(
      openIdConfig: Uri,
      wk: WellKnown,
      rev: Long,
      deprecated: Boolean = false,
      logo: Option[Uri] = None,
      subject: Subject = Anonymous
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
      subject,
      Instant.EPOCH,
      subject
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
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  ): RealmResource = {
    val wk = WellKnown(
      realm.issuer,
      realm.grantTypes,
      realm.keys,
      realm.authorizationEndpoint,
      realm.tokenEndpoint,
      realm.userInfoEndpoint,
      realm.revocationEndpoint,
      realm.endSessionEndpoint
    )
    currentState(realm.openIdConfig, wk, rev, deprecated, realm.logo, subject)
      .copy(label = realm.label, name = realm.name)
      .toResource
      .value
  }

}
