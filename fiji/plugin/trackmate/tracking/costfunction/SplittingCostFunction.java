package fiji.plugin.trackmate.tracking.costfunction;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import Jama.Matrix;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotFeature;
import fiji.plugin.trackmate.tracking.LAPTracker;
import fiji.plugin.trackmate.tracking.TrackerSettings;

/**
 * <p>Splitting cost function used with {@link LAPTracker}.
 * 
 * <p>The <b>cost function</b> is:
 * 
 * <p><code>d^2 * p</code>, p > 1
 * <p><code>d^2 * (1/(p^2))</code>, p < 1
 * 
 * <p>d = euclidean distance between two objects
 * <p>p = (intensity of middle point, frame t) / ((intensity of start point, frame t+1) + (intensity of middle point, frame t + 1))
 * 
 * <p>The <b>thresholds</b> used are:
 * <ul>
 * <li>Must be within a certain number of frames.</li>
 * <li>Must be within a certain distance.</li>
 * <li>p, the intensity ratio, must be within a certain range</li>
 * </ul>
 * 
 * See equations (5) and (6) in the paper.
 * 
 * @author Nicholas Perry
 *
 */
public class SplittingCostFunction {

	private static final boolean DEBUG = false;

	/** The time cutoff */
	protected double timeCutoff;
	/** The distance threshold. */
	protected double maxDist;
	/** The value used to block an assignment in the cost matrix. */
	protected double blocked;
	/** Thresholds for the feature ratios. */
	protected Map<SpotFeature, Double> featureCutoffs;

	private boolean allowSplitting;

	/** A flag stating if we should use multi--threading for some calculations. */
	protected boolean useMultithreading = fiji.plugin.trackmate.TrackMate_.DEFAULT_USE_MULTITHREADING;


	/*
	 * CONSTRUCTOR
	 */


	public SplittingCostFunction(TrackerSettings settings) {
		this.allowSplitting 	= settings.allowSplitting;
		this.maxDist 			= settings.splittingDistanceCutoff;
		this.timeCutoff 		= settings.splittingTimeCutoff;
		this.blocked 			= settings.blockingValue;
		this.featureCutoffs 	= settings.splittingFeatureCutoffs;
	}

	/*
	 * METHODS
	 */


	public Matrix getCostFunction(final List<SortedSet<Spot>> trackSegments, final List<Spot> middlePoints) {

		if (DEBUG)
			System.out.println("-- DEBUG information from SplittingCostFunction --");

		if (!allowSplitting)
			return new Matrix(middlePoints.size(), trackSegments.size(), blocked);

		// Prepare threads
		final Thread[] threads;
		if (useMultithreading) {
			threads = SimpleMultiThreading.newThreads();
		} else {
			threads = SimpleMultiThreading.newThreads(1);
		}

		final Matrix m = new Matrix(middlePoints.size(), trackSegments.size());

		// Prepare the thread array
		final AtomicInteger ai = new AtomicInteger(0);
		for (int ithread = 0; ithread < threads.length; ithread++) {

			threads[ithread] = new Thread("LAPTracker splitting cost thread "+(1+ithread)+"/"+threads.length) {  

				public void run() {

					for (int i = ai.getAndIncrement(); i < middlePoints.size(); i = ai.getAndIncrement()) {

						Spot middle = middlePoints.get(i);
						if (DEBUG)
							System.out.println(String.format("Current middle spot: x=%.1f, y=%.1f, t=%.1f", 
									middle.getPosition(null)[0], middle.getPosition(null)[1], middle.getFeature(SpotFeature.POSITION_T)));

						for (int j = 0; j < trackSegments.size(); j++) {

							SortedSet<Spot> track = trackSegments.get(j);
							Spot start = track.first();

							if (DEBUG)
								System.out.println("Segment "+j);
							if (track.contains(middle)) {	
								m.set(i, j, blocked);
								continue;
							}

							// Frame threshold - middle Spot must be one frame behind of the start Spot
							Float tstart = start.getFeature(SpotFeature.POSITION_T);
							Float tmiddle = middle.getFeature(SpotFeature.POSITION_T);
							if ( (tstart - tmiddle > timeCutoff) || (tstart - tmiddle <= 0) ) {
								m.set(i, j, blocked);
								continue;
							}

							// Radius threshold
							Float d2 = start.squareDistanceTo(middle);
							if (d2 > maxDist*maxDist) {
								m.set(i, j, blocked);
								continue;
							}

							// Initial cost
							double s = d2;

							// Update cost with feature costs
							for (SpotFeature feature : featureCutoffs.keySet()) {

								// Larger than 0, equals 0 is the 2 intensities are the same
								Float iRatio = start.normalizeDiffTo(middle, feature);
								if (iRatio > featureCutoffs.get(feature)) {
									s = blocked;
									break;
								}

								// Set score
								s *= (1 + iRatio);
							}

							m.set(i, j, s);
						}
					}
				}
			};
		}
		
		SimpleMultiThreading.startAndJoin(threads);
		
		return m;
	}
}
