package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{LocalStackS3StorageClient, S3Helpers}
import ch.epfl.bluebrain.nexus.ship.files.FileCopier.localDiskPath
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.AnyFixture

class FileCopierSuite extends NexusSuite with LocalStackS3StorageClient.Fixture with S3Helpers {

  override def munitFixtures: Seq[AnyFixture[_]]                                                             = List(localStackS3Client)
  implicit private lazy val (s3Client: S3StorageClient, underlying: S3AsyncClientOp[IO], _: S3StorageConfig) =
    localStackS3Client()

  private val fileContents = "file content"

  test("Correctly decode a local path") {
    val encoded  = Uri.Path("org/proj/9/f/0/3/2/4/f/e/0925_Rhi13.3.13%20cell%201+2%20(superficial).asc")
    val obtained = localDiskPath(encoded)
    val expected = "org/proj/9/f/0/3/2/4/f/e/0925_Rhi13.3.13 cell 1+2 (superficial).asc"
    assertEquals(obtained, expected)
  }

  test("Correctly decode another local path with a space") {
    val encoded  = Uri.Path("org/proj/9/2/f/f/f/2/1/6/566647353__Square%20-%200.5ms%20Subthreshold__stimulus__1.png")
    val obtained = localDiskPath(encoded)
    val expected = "org/proj/9/2/f/f/f/2/1/6/566647353__Square - 0.5ms Subthreshold__stimulus__1.png"
    assertEquals(obtained, expected)
  }

  test("Should find a file from a decoded path") {
    givenAnS3Bucket { bucket =>
      val path = Uri.Path(
        "bbp/ncmv3/1/a/e/4/1/a/b/6/EMC__emodel=L5_TPC:B_cAC__ttype=182_L45%20IT%20CTX__species=mouse__brain_region=SS__iteration=mettypesv12_1.json"
      )
      val key  =
        "bbp/ncmv3/1/a/e/4/1/a/b/6/EMC__emodel=L5_TPC:B_cAC__ttype=182_L45 IT CTX__species=mouse__brain_region=SS__iteration=mettypesv12_1.json"
      givenAFileInABucket(bucket, key, fileContents) { _ =>
        FileCopier
          .computeOriginKey(s3Client, bucket, path, localOrigin = false)
          .assertEquals(
            Some(key)
          )
      }
    }
  }

  test("Should handle correctly path from local origin") {
    givenAnS3Bucket { bucket =>
      val path = Uri.Path("27554ab5-20f4-4973-91f6-0b2d990cea69/b/e/4/9/b/5/5/b/Log2(CPM(Exon+intron)+1)_Visp_Pyr.csv")
      val key  = "27554ab5-20f4-4973-91f6-0b2d990cea69/b/e/4/9/b/5/5/b/Log2(CPM(Exon+intron)+1)_Visp_Pyr.csv"
      givenAFileInABucket(bucket, key, fileContents) { _ =>
        FileCopier
          .computeOriginKey(s3Client, bucket, path, localOrigin = true)
          .assertEquals(
            Some(key)
          )
      }
    }
  }

  test("Should handle correctly another path from local origin") {
    givenAnS3Bucket { bucket =>
      val path = Uri.Path(
        "95b0ee1e-a6a5-43e9-85fb-938b3c38dfc0/9/f/0/3/2/4/f/e/0925_Rhi13.3.13%20cell%201+2%20(superficial).asc"
      )
      val key  = "95b0ee1e-a6a5-43e9-85fb-938b3c38dfc0/9/f/0/3/2/4/f/e/0925_Rhi13.3.13 cell 1+2 (superficial).asc"
      givenAFileInABucket(bucket, key, fileContents) { _ =>
        FileCopier
          .computeOriginKey(s3Client, bucket, path, localOrigin = true)
          .assertEquals(
            Some(key)
          )
      }
    }
  }

  test("Should handle correctly yet another path from local origin") {
    givenAnS3Bucket { bucket =>
      val path = Uri.Path(
        "c1220611-7415-4476-baee-36e75f87bdeb/6/7/5/f/f/c/8/f/AIBS_morpho+ephys_data(for_Rat_coclustering).csv"
      )
      val key  = "c1220611-7415-4476-baee-36e75f87bdeb/6/7/5/f/f/c/8/f/AIBS_morpho+ephys_data(for_Rat_coclustering).csv"
      givenAFileInABucket(bucket, key, fileContents) { _ =>
        FileCopier
          .computeOriginKey(s3Client, bucket, path, localOrigin = true)
          .assertEquals(
            Some(key)
          )
      }
    }
  }

  test("Should fallback to the encoded path if the decoded is not found") {
    givenAnS3Bucket { bucket =>
      val path = Uri.Path("15849bfc-f2ef-4ddd-89cb-b4658eb1f4ab/5/4/6/7/8/a/0/9/%20P(marker_cre)_overlapping.csv")
      val key  = "15849bfc-f2ef-4ddd-89cb-b4658eb1f4ab/5/4/6/7/8/a/0/9/%20P(marker_cre)_overlapping.csv"
      givenAFileInABucket(bucket, key, fileContents) { _ =>
        FileCopier
          .computeOriginKey(s3Client, bucket, path, localOrigin = false)
          .assertEquals(
            Some(key)
          )
      }
    }
  }

  test("Should skip directories") {
    givenAnS3Bucket { bucket =>
      val path = Uri.Path(
        "bbp/ncmv3/1/a/e/4/1/a/b/6/EMC__emodel=L5_TPC:B_cAC__ttype=182_L45%20IT%20CTX__species=mouse__brain_region=SS__iteration=mettypesv12_1.json"
      )
      val key  =
        "bbp/ncmv3/1/a/e/4/1/a/b/6/EMC__emodel=L5_TPC:B_cAC__ttype=182_L45 IT CTX__species=mouse__brain_region=SS__iteration=mettypesv12_1.json/pouet.json"
      givenAFileInABucket(bucket, key, fileContents) { _ =>
        FileCopier.computeOriginKey(s3Client, bucket, path, localOrigin = false).assertEquals(None)
      }
    }
  }

}
