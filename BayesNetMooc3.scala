package BIDMach.models

import BIDMat.{ CMat, CSMat, DMat, Dict, IDict, FMat, GMat, GIMat, GSMat, HMat, IMat, Mat, SMat, SBMat, SDMat }
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMat.Solvers._
import BIDMat.Plotting._
import java.io._
import scala.util.Random

/**
 * A Bayesian Network implementation with fast parallel Gibbs Sampling on a MOOC dataset.
 * 
 * Haoyu Chen and Daniel Seita are building off of Huasha Zhao's original code.
 */

class BayesNetMooc3(val dag:Mat, val statesPerNode:Mat, val opts:BayesNetMooc3.Opts = new BayesNetMooc3.Options) extends Model(opts) {
  var graph: Graph = null
  var mm:Mat = null       // the cpt in our code
  var iproject:Mat = null
  var pproject:Mat = null
  var cptOffset:Mat = null


  /* this is the init method; it does the same tasks in our setup method, but it will also init the state by randomly sample
   * some numbers. The filling unknown elements parts is the same as the "initState()" method in the old code.

  */
  def init() = {
    graph = dag match {
      case dd:SMat => new Graph(dd, opts.dim, statesPerNode)
      case _ => throw new RuntimeException("dag not SMat")
    }
    graph.color

    // build the iproj and pproj inside the graph
    iproject = if (opts.useGPU && Mat.hasCUDA > 0) GMat(graph.iproject) else graph.iproject
    pproject = if (opts.useGPU && Mat.hasCUDA > 0) GMat(graph.pproject) else graph.pproject

    // build the cpt as the modelmats
    // build the cptoffset
    val numSlotsInCpt = if (opts.useGPU && Mat.hasCUDA > 0) GIMat(exp(GMat((pproject.t)) * ln(GMat(statesPerNode))) + 1e-3) else IMat(exp(DMat(full(pproject.t)) * ln(DMat(statesPerNode))) + 1e-3)     
    val lengthCPT = sum(numSlotsInCpt).v
    var cptOffset = izeros(graph.n, 1)
    var cptOffset(1 until graph.n) = cumsum(numSlotsInCpt)(0 until graph.n-1)
    var cpt = rand(lengthCPT,1)

    for (i <- 0 until graph.n-1) {
      var offset = cptOffset(i)
      val endOffset = cptOffset(i+1)
      while (offset < endOffset) {
        val normConst = sum( cpt(offset until offset+statesPerNode(i)) )
        cpt(offset until offset+statesPerNode(i)) = cpt(offset until offset+statesPerNode(i)) / normConst
        offset = offset + statesPerNode(i)
      }
    }
    var lastOffset = cptOffset(graph.n-1)
    while (lastOffset < cpt.length) {
      val normConst = sum( cpt(lastOffset until lastOffset+statesPerNode(graph.n-1)) )
      cpt(lastOffset until lastOffset+statesPerNode(graph.n-1)) = cpt(lastOffset until lastOffset+statesPerNode(graph.n-1)) / normConst
      lastOffset = lastOffset + statesPerNode(graph.n-1)
    }
    setmodelmats(new Array[Mat](1))
    modelmats(0) = if (opts.useGPU && Mat.hasCUDA > 0) GMat(cpt) else cpt
        
    // init the data here, i.e. revise the data into the our format, i.e. randomnize the unknown elements
    // I think it will read each matrix and init the state, and putback to the datarescource. 
    // it would be similar as what we wrote in initState()
    // here it has one more variable: opts.nsampls, it's like the number of traning samples
    if (mats.size > 1) {
      while (datasource.hasNext) {
        mats = datasource.next
        val sdata = mats(0)
        val state = mats(1)

        state <-- rand(state.nrows, state.ncols * opts.nsampls)

        for (row <- 0 until state.nrows) {
          state(row,?) = min(FMat(IMat(statesPerNode(row)*state(row,?))), statesPerNode(row)-1)
        }

        val innz = sdata match { 
          case ss: SMat => find(ss >= 0)
          case ss: GSMat => find(SMat(ss) >= 0)
          case _ => throw new RuntimeException("sdata not SMat/GSMat")
        }

        for(i <- 0 until opts.nsampls){
          state.asInstanceOf[FMat](innz + i * sdata.ncols *  graph.n) = 0f
          state(?, i*sdata.ncols until (i+1)*sdata.ncols) = state(?, i*sdata.ncols until (i+1)*sdata.ncols) + (sdata.asInstanceOf[SMat](innz))

        }
        datasource.putBack(mats,1)
      }
    }
    // I think here we do not need to store the "globalPMatrices", since the param of our model is just cpt and graph
    mm = modelmats(0)
    updatemats = new Array[Mat](1)
    updatemats(0) = mm.zeros(mm.nrows, mm.ncols)
  }


  /** Returns a conditional probability table specified by indices from the "index" matrix. */
  def getCpt(index: Mat) = {
    var cptindex = mm.zeros(index.nrows, index.ncols)
    for(i <-0 until index.ncols){
      cptindex(?, i) = mm(IMat(index(?, i)))
    }
    cptindex
  }

  // this method do the sampling it's equavelent the old method: "sample"
  def uupdate(sdata:Mat, user:Mat, ipass:Int):Unit = {
    if (putBack < 0 || ipass == 0) user.set(1f)     // I am not sure, what does this line mean...

    for (k <- 0 until opts.uiter) {
      
      for(c <- 0 until graph.ncolors){


        val idInColor = find(graph.colors == c)
        val numState = IMat(maxi(maxi(statesPerNode(idInColor),1),2)).v
        var stateSet = new Array[FMat](numState)
        var pSet = new Array[FMat](numState)
        // here I change the dim of the pMatrix: I add the opts.nsampls
        var pMatrix = zeros(idInColor.length, sdata.ncols * opts.nsampls)
        for (i <- 0 until numState) {
          val saveID = find(statesPerNode(idInColor) > i)
          val ids = idInColor(saveID)
          val pids = find(sum(pproject(ids, ?), 1))
          initStateColor(sdata, ids, i, stateSet, user)
          computeP(ids, pids, i, pSet, pMatrix, stateSet(i), saveID, idInColor.length)
        }
        sampleColor(sdata, numState, idInColor, pSet, pMatrix)

      } 
    } 
  }
  
  /**
   * Initializes the statei matrix for this particular color group and for this particular value.
   * It fills in the unknown values at the ids locations with i, then we can use it in computeP.
   * 
   * @param fdata Training data matrix, with unknowns of -1 and known values in {0,1,...,k}.
   * @param ids Indices of nodes in this color group that can also attain value/state i.
   * @param i An integer representing a value/state (we use these terms interchangeably).
   * @param stateSet An array of statei matrices, each of which has "i" in the unknowns of "ids".
   */

   // the things I changed here is to 1) replace the state by user mat; and 2) update the compute of innz.
   // 3) cast the statei mat to FMat and 4) add the nsamples
  def initStateColor(fdata: Mat, ids: IMat, i: Int, stateSet: Array[FMat], user:Mat) = {
    var statei = user.copy
    statei.asInstanceOf[FMat](ids,?) = i
    if (!checkState(statei.asInstanceOf[FMat])) {
      println("problem with initStateColor(), max elem is " + maxi(maxi(statei,1),2).dv)
    }
    val innz = fdata match {
      case ss: SMat => find(fdata >= 0)
      case ss: GMat => find(SMat(fdata) >= 0)
    }
    //val innz = find(fdata >= 0)
    //statei.asInstanceOf[FMat](innz) = 0f
    //statei(innz) <-- statei(innz) + fdata(innz)

    val innz = find(fdata)
    for (i <- 0 until opts.nsampls) {
      //statei.asInstanceOf[FMat](innz + i * fdata.ncols * graph.n) = 0f
      statei.asInstanceOf[FMat](innz + i * fdata.ncols * graph.n) <--  fdata.asInstanceOf[SMat](innz)
    }


    if (!checkState(statei)) {
      println("problem with end of initStateColor(), max elem is " + maxi(maxi(statei,1),2).dv)
    }
    stateSet(i) = statei
  }

  /** 
   * Computes the un-normalized probability matrix for attaining a particular state. We also do
   * the cumulative sum for pMatrix so we can eventually use it as a normalizing constant.
   * 
   * @param ids Indices of nodes in this color group that can also attain value/state i.
   * @param pids Indices of nodes in "ids" AND the union of all the children of "ids" nodes.
   * @param i An integer representing a value/state (we use these terms interchangeably).
   * @param pSet The array of matrices, each of which represents probabilities of nodes attaining i.
   * @param pMatrix The matrix that represents normalizing constants for probabilities.
   * @param statei The matrix with unknown values at "ids" locations of "i".
   * @param saveID Indices of nodes in this color group that can attain i. (TODO Is this needed?)
   * @param numPi The number of nodes in the color group of "ids", including those that can't get i.
   */

   // the changes I made here are 1) use the opts.eps to replace the 1e-10, change the dim of the pSet,
   // i.e. the ncols of pSet is batchsize * nsampls

  def computeP(ids: IMat, pids: IMat, i: Int, pSet: Array[FMat], pMatrix: FMat, statei: FMat, saveID: IMat, numPi: Int) = {
    val a = cptOffset(pids) + IMat(iproject(pids, ?) * statei)
    val b = maxi(maxi(a,1),2).dv
    if (b >= cpt.length) {
      println("ERROR! In computeP(), we have max index " + b + ", but cpt.length = " + cpt.length)
    }
    val nodei = ln(getCpt(cptOffset(pids) + IMat(iproject(pids, ?) * statei)) + opts.eps)
    var pii = zeros(numPi, statei.ncols)
    pii(saveID, ?) = exp(pproject(ids, pids) * nodei)
    pSet(i) = pii
    pMatrix(saveID, ?) = pMatrix(saveID, ?) + pii(saveID, ?)
  }

  /** 
   * For a given color group, after we have gone through all its state possibilities, we sample it.
   * 
   * To start, we use a matrix of random values. Then, we go through each of the possible states and
   * if random values fall in a certain range, we assign the range's corresponding integer {0,1,...,k}.
   * Important! BEFORE changing pSet(i)'s, store them in the globalPMatrices to save for later.
   * 
   * @param fdata Training data matrix, with unknowns of -1 and known values in {0,1,...,k}.
   * @param numState The maximum number of state/values possible of any variable in this color group.
   * @param idInColor Indices of nodes in this color group.
   * @param pSet The array of matrices, each of which represents probabilities of nodes attaining i.
   * @param pMatrix The matrix that represents normalizing constants for probabilities.
   */


   // changes I made in this part: 1) delete the globalPMatrices parts; 2) use the user to replace the state matrix
   // 3) add the for loop for the nsampls; 4) fdata change type to be Mat

  def sampleColor(fdata: Mat, numState: Int, idInColor: IMat, pSet: Array[FMat], pMatrix: FMat, user: Mat) = {
    
    // Put inside globalPMatrices now because later we overwrite these pSet(i) matrices.
    // For this particular sampling, we are only concerned with idInColor nodes.
    //for (i <- 0 until numState) {
    //  globalPMatrices(i)(idInColor,?) = pSet(i).copy
    //}
    
    val sampleMatrix = rand(idInColor.length, fdata.ncols * opts.nsampls)
    pSet(0) = pSet(0) / pMatrix
    user(idInColor,?) <-- 0 * user(idInColor,?)
    
    // Each time, we check to make sure it's <= pSet(i), but ALSO exceeds the previous \sum (pSet(j)).
    
    for (i <- 1 until numState) {
      val saveID = find(statesPerNode(idInColor) > i)
      //val saveID_before = find(statesPerNode(idInColor) > (i - 1))
      val ids = idInColor(saveID)
      val pids = find(sum(pproject(ids, ?), 1))
      pSet(i) = (pSet(i) / pMatrix) + pSet(i-1) // Normalize and get the cumulative prob
      // Use Hadamard product to ensure that both requirements are held.
      user(ids, ?) = user(ids,?) + i * ((sampleMatrix(saveID, ?) <= pSet(i)(saveID, ?)) *@ (sampleMatrix(saveID, ?) >= pSet(i - 1)(saveID, ?)))
      if (!checkState(user)) {
        println("problem with loop in sampleColor(), max elem is " + maxi(maxi(user,1),2).dv)
      }
    }

    // Finally, re-write the known state into the state matrix
    val saveIndex = find(fdata >= 0)
    for (j <- 0 until opts.nsampls) {
      user.asInstanceOf[FMat](saveIndex + i * fdata.ncols * graph.n) <--  fdata.asInstanceOf[SMat](saveIndex)
    }
    
    if (!checkState(user)) {
      println("problem with end of sampleColor(), max elem is " + maxi(maxi(user,1),2).dv)
    }
  }

  // I think this method is equavelent to our method: "updateCpt"
  def mupdate(sdata:Mat, user:Mat, ipass:Int):Unit = {

  }


  /** Returns FALSE if there's an element at least size 2, which is BAD. */
  def checkState(state: Mat) : Boolean = {
    val a = maxi(maxi(state,2),1).dv
    if (a >= 2) {
      return false
    }
    return true
  }

}


/**
 * A graph structure for Bayesian Networks. Includes features for:
 * 
 *   (1) moralizing graphs, 'moral' matrix must be (i,j) = 1 means node i is connected to node j
 *   (2) coloring moralized graphs, not sure why there is a maxColor here, though...
 *
 * @param dag An adjacency matrix with a 1 at (i,j) if node i has an edge TOWARDS node j.
 * @param n The number of vertices in the graph. 
 */

 // the change I made here: 1) add one more input statesPerNode, 2) add two methods, return the pproject and iproject

class Graph(val dag: SMat, val n: Int, val statesPerNode: IMat) {
 
  var mrf: FMat = null
  var colors: IMat = null
  var ncolors = 0
  val maxColor = 100
   
  /**
   * Connects the parents of a certain node, a single step in the process of moralizing the graph.
   * 
   * Iterates through the parent indices and insert 1s in the 'moral' matrix to indicate an edge.
   * 
   * TODO Is there a way to make this GPU-friendly?
   * 
   * @param moral A matrix that represents an adjacency matrix "in progress" in the sense that it
   *    is continually getting updated each iteration from the "moralize" method.
   * @param parents An array representing the parent indices of the node of interest.
   */
  def connectParents(moral: FMat, parents: IMat) = {
    val l = parents.length
    for(i <- 0 until l)
      for(j <- 0 until l){
        if(parents(i) != parents(j)){
          moral(parents(i), parents(j)) = 1f
        }
      }
    moral
  } 
  
  /**
   * TODO No idea what this is yet, and it isn't in the newgibbs branch. Only used here for BayesNet.
   */
  def iproject = {
    pproj = pproject
    var res = (pproj).t
    for (i <- 0 until n) {
      val parents = find(pproj(?, i))
      var cumRes = 1
      val partentsLen = parents.length
      for (j <- 1 until partentsLen) {
        cumRes = cumRes * statesPerNode(parents(partentsLen - j))
        res(i, parents(partentsLen - j - 1)) = cumRes.toFloat
      }
    }
    res
    /**
    var ip = dag.t       
    for (i <- 0 until n) {
      val ps = find(dag(?, i))
      val np = ps.length    
      for (j <-0 until np) {
        ip(i, ps(j)) = math.pow(2, np-j).toFloat
      }
    }
    ip + sparse(IMat(0 until n), IMat(0 until n), ones(1, n))
    **/
  }
  
  /**
   * TODO No idea what this is yet, and it isn't in the new gibbs branch. Only used here for BayesNet.
   */
  def pproject = {
    dag + sparse(IMat(0 until n), IMat(0 until n), ones(1, n))
  }
  
  /**
   * Moralize the graph.
   * 
   * This means we convert the graph from directed to undirected and connect parents of nodes in 
   * the directed graph. First, copy the dag to the moral graph because all 1s in the dag matrix
   * are 1s in the moral matrix (these are adjacency matrices). For each node, find its parents,
   * connect them, and update the matrix. Then make it symmetric because the graph is undirected.
   * 
   * TODO Is there a way to make this GPU-friendly?
   */
  def moralize = {
    var moral = full(dag)
    for (i <- 0 until n) {
      var parents = find(dag(?, i))
      moral = connectParents(moral, parents)
    }
    mrf = ((moral + moral.t) > 0)
  }
  
  /**
   * Sequentially colors the moralized graph of the dag so that one can run parallel Gibbs sampling.
   * 
   * Steps: first, moralize the graph. Then iterate through each node, find its neighbors, and apply a
   * "color mask" to ensure current node doesn't have any of those colors. Then find the legal color
   * with least count (a useful heuristic). If that's not possible, then increase "ncolor".
   * 
   * TODO Is there a way to make this GPU-friendly?
   */
  def color = {
    moralize
    var colorCount = izeros(maxColor, 1)
    colors = -1 * iones(n, 1)
    ncolors = 0
   
    // Access nodes sequentially. Find the color map of its neighbors, then find the legal color w/least count
    val seq = IMat(0 until n)
    // Can also access nodes randomly
    // val r = rand(n, 1); val (v, seq) = sort2(r)

    for (i <- 0 until n) {
      var node = seq(i)
      var nbs = find(mrf(?, node))
      var colorMap = iones(ncolors, 1)
      for (j <- 0 until nbs.length) {
        if (colors(nbs(j)) > -1) {
          colorMap(colors(nbs(j))) = 0
        }
      }
      var c = -1
      var minc = 999999
      for (k <- 0 until ncolors) {
        if ((colorMap(k) > 0) && (colorCount(k) < minc)) {
          c = k
          minc = colorCount(k)
        }
      }
      if (c == -1) {
       c = ncolors
       ncolors = ncolors + 1
      }
      colors(node) = c
      colorCount(c) += 1
    }
    colors
  }
 
}



