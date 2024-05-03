package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.ship.ProjectMapper
import ch.epfl.bluebrain.nexus.ship.resources.SourcePatcher.removeEmptyIds
import io.circe.Json
import io.circe.optics.JsonPath.root

final class SourcePatcher(distributionPatcher: DistributionPatcher) {

  def apply(json: Json): IO[Json] = distributionPatcher.singleOrArray(json).map(removeEmptyIds)

}

object SourcePatcher {

  private val emptyString = Json.fromString("")

  // Remove empty ids from sources as they are not accepted anymore in payloads
  // Resources will be keep their ids as we explicitly pass them along the source payload
  val removeEmptyIds: Json => Json = root.obj.modify {
    case obj if obj("@id").contains(emptyString) => obj.remove("@id")
    case obj => obj
  }

  def apply(fileSelfParser: FileSelf, projectMapper: ProjectMapper, targetBase: BaseUri): SourcePatcher =
    new SourcePatcher(new DistributionPatcher(fileSelfParser, projectMapper, targetBase))
}
