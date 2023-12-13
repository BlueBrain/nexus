package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileId
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FetchFileResource, FileResource}

object FetchFileResourceMock {

  def unimplemented: FetchFileResource = withMockedFetch(_ => IO(???))

  def withMockedFetch(fetchMock: FileId => IO[FileResource]): FetchFileResource = fetchMock(_)

}
