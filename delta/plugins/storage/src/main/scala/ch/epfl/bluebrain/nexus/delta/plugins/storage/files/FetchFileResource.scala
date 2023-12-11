package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileId

trait FetchFileResource {
  /**
   * Fetch the last version of a file
   *
   * @param id
   *  the identifier that will be expanded to the Iri of the file with its optional rev/tag
   */
  def fetch(id: FileId): IO[FileResource]
}
