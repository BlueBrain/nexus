package ch.epfl.bluebrain.nexus.delta.sdk.provisioning

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, PrefixIri, ProjectFields}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import pureconfig.ConfigReader
import pureconfig.configurable._
import pureconfig.error.CannotConvert

/**
  * Configuration of automatic provisioning of projects.
  *
  * @param enabled
  *   flag signalling whether automatic provisioning is enabled
  * @param permissions
  *   the permissions applied to the newly provisioned project
  * @param enabledRealms
  *   the realms for which the provisioning is enabled(map of realm label to organization in which the projects for the
  *   realm should be created)
  * @param fields
  *   the project configuration
  */
final case class AutomaticProvisioningConfig(
    enabled: Boolean,
    permissions: Set[Permission],
    enabledRealms: Map[Label, Label],
    fields: ProjectFields
)

object AutomaticProvisioningConfig {

  val disabled: AutomaticProvisioningConfig = AutomaticProvisioningConfig(
    enabled = false,
    permissions = Set.empty,
    enabledRealms = Map.empty,
    ProjectFields(None, ApiMappings.empty, None, None)
  )

  implicit private val permissionConfigReader: ConfigReader[Permission] =
    ConfigReader.fromString(str =>
      Permission(str).leftMap(err => CannotConvert(str, classOf[Permission].getSimpleName, err.getMessage))
    )

  implicit private val iriConfigReader: ConfigReader[Iri] =
    ConfigReader.fromString(str => Iri(str).leftMap(err => CannotConvert(str, classOf[Iri].getSimpleName, err)))

  implicit private val labelConfigReader: ConfigReader[Label] = ConfigReader.fromString(str =>
    Label(str).leftMap(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage))
  )

  implicit private val mapReader: ConfigReader[Map[Label, Label]] =
    genericMapReader(str => Label(str).leftMap(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage)))

  implicit private val prefixIriReader: ConfigReader[PrefixIri] = ConfigReader.fromString { str =>
    (for {
      iri       <- Iri(str)
      prefixIri <- PrefixIri(iri).leftMap(_.getMessage)
    } yield prefixIri).leftMap(e => CannotConvert(str, classOf[PrefixIri].getSimpleName, e))
  }

  implicit private val apiMappingsReader: ConfigReader[ApiMappings] = ConfigReader[Map[String, Iri]].map(ApiMappings(_))

  implicit val provisioningConfigReader: ConfigReader[AutomaticProvisioningConfig] = ConfigReader.fromCursor { cursor =>
    for {
      obj                 <- cursor.asObjectCursor
      enabled             <- obj.atKey("enabled").flatMap(_.asBoolean)
      permissionsCursor   <- obj.atKey("permissions")
      permissions         <- ConfigReader[Set[Permission]].from(permissionsCursor)
      enabledRealmsCursor <- obj.atKey("enabled-realms").flatMap(_.asObjectCursor)
      enabledRealms       <- ConfigReader[Map[Label, Label]].from(enabledRealmsCursor)
      description         <- obj.atKey("description").flatMap(_.asString)
      apiMappingsCursor   <- obj.atKey("api-mappings").flatMap(_.asObjectCursor)
      apiMappings         <- ConfigReader[ApiMappings].from(apiMappingsCursor)
      baseCursor           = obj.atKeyOrUndefined("base")
      base                <- ConfigReader[Option[PrefixIri]].from(baseCursor)
      vocabCursor          = obj.atKeyOrUndefined("vocab")
      vocab               <- ConfigReader[Option[PrefixIri]].from(vocabCursor)
    } yield AutomaticProvisioningConfig(
      enabled,
      permissions,
      enabledRealms,
      ProjectFields(Some(description), apiMappings, base, vocab)
    )
  }
}
