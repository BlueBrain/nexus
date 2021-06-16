package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.CacheIndexingConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectsConfig.AutomaticProvisioningConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, SaveProgressConfig}
import pureconfig.ConfigReader
import pureconfig.configurable._
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto._

import scala.annotation.nowarn

/**
  * Configuration for the Projects module.
  *
  * @param aggregate             configuration of the underlying aggregate
  * @param keyValueStore         configuration of the underlying key/value store
  * @param pagination            configuration for how pagination should behave in listing operations
  * @param cacheIndexing         configuration of the cache indexing process
  * @param persistProgressConfig configuration for the persistence of progress of projections
  * @param automaticProvisioning configuration for automatic provisioning of projects
  */
final case class ProjectsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    persistProgressConfig: SaveProgressConfig,
    automaticProvisioning: AutomaticProvisioningConfig
)

object ProjectsConfig {

  /**
    * Configuration of automatic provisioning of projects.
    *
    * @param enabled      flag signalling whether automatic provisioning is enabled
    * @param permissions  the permissions applied to the newly provisioned project
    * @param enabledReams the realms for which the provisioning is enabled(map of realm label to organization in which the projects for the realm should be created)
    * @param description  the description of the created project
    * @param apiMappings  the [[ApiMappings]] of the created project
    * @param base         the base for the created project
    * @param vocab        the vocab for the created project
    */
  final case class AutomaticProvisioningConfig(
      enabled: Boolean,
      permissions: Set[Permission],
      enabledReams: Map[Label, Label],
      description: String,
      apiMappings: ApiMappings,
      base: PrefixIri,
      vocab: PrefixIri
  )

  object AutomaticProvisioningConfig {

    val disabled = AutomaticProvisioningConfig(
      enabled = false,
      permissions = Set.empty,
      enabledReams = Map.empty,
      description = "",
      apiMappings = ApiMappings.empty,
      base = PrefixIri.unsafe(iri"http://localhost:8080/"),
      vocab = PrefixIri.unsafe(iri"http://localhost:8080/")
    )

  }

  @nowarn("cat=unused")
  implicit private val permissionConfigReader: ConfigReader[Permission] =
    ConfigReader.fromString(str =>
      Permission(str).leftMap(err => CannotConvert(str, classOf[Permission].getSimpleName, err.getMessage))
    )

  @nowarn("cat=unused")
  implicit private val iriConfigReader: ConfigReader[Iri] =
    ConfigReader.fromString(str => Iri(str).leftMap(err => CannotConvert(str, classOf[Iri].getSimpleName, err)))

  @nowarn("cat=unused")
  implicit private val labelConfigReader: ConfigReader[Label] = ConfigReader.fromString(str =>
    Label(str).leftMap(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage))
  )

  @nowarn("cat=unused")
  implicit private val mapReader: ConfigReader[Map[Label, Label]] =
    genericMapReader(str => Label(str).leftMap(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage)))

  @nowarn("cat=unused")
  implicit private val prefixIriReader: ConfigReader[PrefixIri] = ConfigReader.fromString { str =>
    (for {
      iri       <- Iri(str)
      prefixIri <- PrefixIri(iri).leftMap(_.getMessage)
    } yield prefixIri).leftMap(e => CannotConvert(str, classOf[PrefixIri].getSimpleName, e))
  }

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
      apiMappings         <- ConfigReader[Map[String, Iri]].from(apiMappingsCursor)
      baseCursor          <- obj.atKey("base")
      base                <- ConfigReader[PrefixIri].from(baseCursor)
      vocabCursor         <- obj.atKey("vocab")
      vocab               <- ConfigReader[PrefixIri].from(vocabCursor)
    } yield AutomaticProvisioningConfig(
      enabled,
      permissions,
      enabledRealms,
      description,
      ApiMappings(apiMappings),
      base,
      vocab
    )
  }
  implicit final val projectConfigReader: ConfigReader[ProjectsConfig]             =
    deriveReader[ProjectsConfig]
}
