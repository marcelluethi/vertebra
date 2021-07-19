package modelling

import com.typesafe.scalalogging.StrictLogging
import data.DataProvider
import data.DataProvider.Stage
import data.DataProvider.Vertebra.VertebraL1
import modelling.BuildSSM.logger
import scalismo.common.interpolation.BSplineImageInterpolator3D
import scalismo.io.StatisticalModelIO
import scalismo.mesh.ScalarVolumeMeshField
import scalismo.numerics.PivotedCholesky
import scalismo.statisticalmodel.{DiscreteLowRankGaussianProcess, PointDistributionModel}
import scalismo.statisticalmodel.dataset.DataCollection
import scalismo.ui.api.ScalismoUI
import scalismo.ui.model.DiscreteLowRankGpPointTransformation

object BuildIntensityModel extends StrictLogging {

  def main(args : Array[String]) : Unit = {
    scalismo.initialize()

    implicit val rng = scalismo.utils.Random(42)

    val dataProvider = DataProvider.of(VertebraL1)

    val referenceMesh = dataProvider.referenceTetrahedralMesh.get

    val (successes, failures) = dataProvider.caseIds
      .map(caseId => dataProvider.tetrahedralMesh(Stage.Registered, caseId)
        .map(mesh => (mesh, caseId))
      )
      .partition(_.isSuccess)

    failures.map(failure =>
      failure.fold(fa => logger.error(fa.getMessage), _ => ())
    )

    val meshesWithId = successes.map(_.get)

    val scalarVolumeMeshFields = for ((tetraMesh, caseId) <- meshesWithId) yield {
      logger.info(s"processing mesh $caseId")
      val volumeCT = dataProvider.volume(Stage.Aligned, caseId).get.interpolate(BSplineImageInterpolator3D(degree = 3))
      val scalars = tetraMesh.pointSet.points.map(volumeCT)
      ScalarVolumeMeshField(referenceMesh, scalars.toIndexedSeq)
    }
    val dataCollection = DataCollection.fromScalarVolumeMesh3DSequence(scalarVolumeMeshFields)
    val intensityModel = DiscreteLowRankGaussianProcess.createUsingPCA(dataCollection, PivotedCholesky.RelativeTolerance(1e-10) )

    logger.info("successfully built intensity model")

    // currently we cannot store these models. We therefore just visualize some samples
    val ui = ScalismoUI()
    val sampleGroup = ui.createGroup("samples")

    val meanIntensity = intensityModel.sample()
    ui.show(sampleGroup, meanIntensity, "mean")
    for (i <- 0 until 10) {
      val sample = intensityModel.sample()

      ui.show(sampleGroup, sample, s"sample-$i")
    }
  }
}