import com.ctc.wstx.evt.WNotationDeclaration;
import com.net2plan.interfaces.networkDesign.IAlgorithm;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.research.niw.networkModel.ImportMetroNetwork;
import com.net2plan.research.niw.networkModel.OpticalSpectrumManager;
import com.net2plan.research.niw.networkModel.WAbstractNetworkElement;
import com.net2plan.research.niw.networkModel.WFiber;
import com.net2plan.research.niw.networkModel.WIpLink;
import com.net2plan.research.niw.networkModel.WLightpathRequest;
import com.net2plan.research.niw.networkModel.WLightpathUnregenerated;
import com.net2plan.research.niw.networkModel.WNet;
import com.net2plan.research.niw.networkModel.WNode;
import com.net2plan.research.niw.networkModel.WServiceChain;
import com.net2plan.research.niw.networkModel.WServiceChainRequest;
import com.net2plan.research.niw.networkModel.WVnfInstance;
import com.net2plan.research.niw.networkModel.WVnfType;
import com.net2plan.utils.InputParameter;
import com.net2plan.utils.Pair;
import com.net2plan.utils.Triple;

import java.io.File;
import java.util.*;

public class DVNFPOnline implements IAlgorithm
{
	/*
		Input parameters that we will need:
			- Linerate per lightpath (in Gbps) -> double (default 40)
			- Occupied slots per lightpath -> int (default 4)
			- Number of shortest paths to compute (K) -> int (defualt 3)
			- Selection of time slot to use -> String (default #select# Morning Afternoon Evening)
	 */
	private InputParameter linerate_Gbps            = new InputParameter("linerate_Gbps", 40.0, "Linerate (in Gbps) per ligthpath", 1.0, true, 1000.0, true);
	private InputParameter slotsPerLightpath        = new InputParameter("slotsPerLightpath", 4, "Number of occupied slots per lightpath", 1, 320);
	private InputParameter K                        = new InputParameter("K", 3, "Number of candidate shortest paths to compute per LP/SC", 1, 100);
	private InputParameter trafficIntensityTimeSlot = new InputParameter("trafficIntensityTimeSlot", "#select# Morning Afternoon Evening Night", "Traffic intensity per time slot (as defined in the design/spreadsheet");
	private InputParameter debug					= new InputParameter("debug",true,  "Debug");
	private InputParameter excelFile				= new InputParameter("excelFile","#file#","Selection of excel spreadsheet");
	public String executeAlgorithm(NetPlan netPlan, Map<String, String> algorithmParameters, Map<String, String> net2planParameters)
	{
		//throw new Net2PlanException ("this is a test");
		
		//First of all, initialize all parameters
		InputParameter.initializeAllInputParameterFieldsOfObject(this, algorithmParameters);
		File excelPath= new File(excelFile.getString());
		WNet wNet= ImportMetroNetwork.importFromExcelFile(excelPath);
		netPlan.copyFrom(wNet.getNetPlan());
		wNet= new WNet(netPlan);
		List <WFiber> SCPath= new ArrayList<WFiber>();
		List<WVnfType>VNFTypeSC = new ArrayList <WVnfType>();
		List <WNode> VNFnodes= new ArrayList <WNode>();
		WNode LastNode = null;
		WNode Src=null,CandidateNode=null;
		int minLengthSrc,minLengthDst, minLength,minnum;
		minLengthSrc=minLengthDst=minnum=minLength=Integer.MAX_VALUE;
		int nodeindex=-1;
		List <WNode> ShortestVNFnodesSrc= new ArrayList <WNode>();
		List <WNode> ShortestVNFnodesDst= new ArrayList <WNode>();
		List <WNode> ShortestVNFnodes= new ArrayList <WNode>();
		List <WNode> MinVNFnodes=new ArrayList <WNode>();
		int round=0;
		
		/* Remove ALL lightpaths */
		for(WLightpathRequest lpr: wNet.getLightpathRequests())
			lpr.remove();

		/* Remove ALL VNF instances */
		for(WVnfInstance vnf: wNet.getVnfInstances())
			vnf.remove();


		/* Remove ALL Service Chains */
		for(WServiceChain sc: wNet.getServiceChains())
				sc.remove();

		/*Any WDM operation must involve the Optical Spectrum Manager, so instantiating the object*/
		OpticalSpectrumManager opt= OpticalSpectrumManager.createFromRegularLps(wNet);
			
		for(WNode node: wNet.getNodes()){
			for(WNode node2: wNet.getNodes()) {
				if(!node.equals(node2)) {
					WLightpathRequest lpr = wNet.addLightpathRequest(node, node2, linerate_Gbps.getDouble(), false);
				}
			}
		}
		
		for(WLightpathRequest lpreq: wNet.getLightpathRequests()) {
			
			List <List<WFiber>> kpath= wNet.getKShortestWdmPath(K.getInt(), lpreq.getA(),lpreq.getB(), Optional.empty());
			
			for(List<WFiber>candidpath: kpath) {
			
			/* for each path you need to find an available wavelength */
			
				
				Optional <SortedSet<Integer>> wl=opt.spectrumAssignment_firstFit(candidpath, slotsPerLightpath.getInt(), Optional.empty());
			
				if(wl.isPresent()) {
					WLightpathUnregenerated lp= lpreq.addLightpathUnregenerated(candidpath, wl.get(), false);
					opt.allocateOccupation(lp, candidpath, wl.get());
					
					//add ip link and couple it
					Pair <WIpLink,WIpLink> iplink=	wNet.addIpLink(lpreq.getA(), lpreq.getB(), linerate_Gbps.getDouble(), false);
					lpreq.coupleToIpLink(iplink.getFirst());
					
					break;
				}	
			}
		
		}
	
		/*to instantiate VNFs on each node. If we do not use them we can remove them later! */

		for (WNode node: wNet.getNodes()) {
			for(WVnfType vnf:wNet.getVnfTypes() )
			 {
				if(vnf.getValidMetroNodesForInstantiation().contains(node.getName()))
				{
					wNet.addVnfInstance(node, vnf.getVnfTypeName(), vnf);
				}
			 }
		}
			

		//to deploy Service Chains: IP links and VNFs */


		for (WServiceChainRequest sc: wNet.getServiceChainRequests())
		{	
			boolean isAllocated=false;	
			//reset VNFTypeSC and VNFnodes
			VNFTypeSC.clear();
			VNFnodes.clear();
				
			System.out.println("The id of service chain requrest is:"+sc.getId());
			for (WNode nodesrc :sc.getPotentiallyValidOrigins() ) {
						
				for (WNode nodedst :sc.getPotentiallyValidDestinations() ) {
					
				//find VNF types of SC one by one
					
						//main cycle for each SC
							for (int i=0; i<sc.getNumberVnfsToTraverse();i++)
							{
							
								for (WVnfType VNFSC : wNet.getVnfTypes()) {
														
									String VNFname=sc.getSequenceVnfTypes().get(i);
									if(VNFSC.getVnfTypeName().equals(VNFname)) {
										if(!VNFTypeSC.contains(VNFSC))
											VNFTypeSC.add(VNFSC);
									
								}
						
								}//end of for VNFs of SC
	
							}//end of for:get VNF types in the network
					
					//for each vnf type, find the nodes that have these VNFs
						for( int i=0;i<VNFTypeSC.size();i++)
						{
							for (WVnfInstance vnfints : wNet.getVnfInstances()) {
									if(VNFTypeSC.get(i).getVnfTypeName().equals(vnfints.getName())) {
										if(!VNFnodes.contains(vnfints))
											VNFnodes.add(vnfints.getHostingNode());		
										
									}
						}

					//if it is the first VNF src is the src of request otherwise it will be replaced with the previous node on SCpath
					if(i!=0) 
						Src=LastNode;
					else
					Src=nodesrc;
						
					//there is already an instance of VNF active in the network
					if(VNFnodes.size()!=0) {
						System.out.println("Size of VNFnodes is: "+VNFnodes.size());
						//choose the node with enough capacity and less length of shortest path value
						for(int j=0;j<VNFnodes.size();j++)
						{
							if(VNFnodes.get(j).getTotalNumCpus()-VNFnodes.get(j).getOccupiedCpus()>=VNFTypeSC.get(i).getOccupCpu())
							{
								//calculate shortest path between src and this node
								//find nodes closer to the src
								List <List<WFiber>> ShortestPathSrc= wNet.getKShortestWdmPath(1, Src, VNFnodes.get(j), Optional.empty());
								if( ShortestPathSrc.size() <= minLengthSrc) {
									minLengthSrc=ShortestPathSrc.size();
									ShortestVNFnodesSrc.add(VNFnodes.get(j));
								}
								//find nodes closer to dst
								List <List<WFiber>> ShortestPathDst= wNet.getKShortestWdmPath(1,VNFnodes.get(j), nodedst, Optional.empty());
								if( ShortestPathDst.size() <= minLengthDst) {
									minLengthDst=ShortestPathDst.size();
									ShortestVNFnodesDst.add(VNFnodes.get(j));
								}
														
								if(	ShortestPathDst.size()+ShortestPathSrc.size()<minLength)
								{
									minLength=ShortestPathDst.size()+ShortestPathSrc.size();
									ShortestVNFnodes.add(VNFnodes.get(j));
								}
							}
						}
						//out of nodes with less length of shortest path choose one with less # of VNFs
						for(int j=0;j<ShortestVNFnodes.size();j++)
						{
							if(ShortestVNFnodes.get(j).getAllVnfInstances().size()<=minnum) {
								minnum=ShortestVNFnodes.get(j).getAllVnfInstances().size();
								MinVNFnodes.add(ShortestVNFnodes.get(j));
							}
						}
											
						//depending on the type of SC choose nodes closer to the src or dst of sc
						if(MinVNFnodes.size()>1&&sc.getUserServiceName()=="Video" && MinVNFnodes.contains(ShortestVNFnodesDst.get(0) ))
							CandidateNode=ShortestVNFnodesDst.get(0);
						else
							if(MinVNFnodes.size()>1 && MinVNFnodes.contains(ShortestVNFnodesSrc.get(0)))
								CandidateNode=ShortestVNFnodesSrc.get(0);
							else
								CandidateNode=MinVNFnodes.get(0);
					}//end of if VNFnodes is not empty

					else
					//there is no already active VNF instance on the network-to be deployed
					{
						System.out.println("The VNFnodes is empty: ");
					}
											
					//function for provisioning vnfs one by one
					List<String> test=new ArrayList<String>();
					test.add(VNFTypeSC.get(i).getVnfTypeName());
					List<List< WAbstractNetworkElement>> list = wNet.getKShortestServiceChainInIpLayer(K.getInt(), 
							Src, CandidateNode,test, Optional.empty(), Optional.empty());
					
					if(list.isEmpty()) {
						System.out.println("it didn't work for this service chain"+sc.getId());			
						continue;
					}

					//Add resource
					sc.addServiceChain(list.get(0), sc.getTrafficIntensityInfo(trafficIntensityTimeSlot.getString()).get());	
					
					//update the lastnode
					LastNode=CandidateNode;
					//check if all the VNFs of SC mapped
					if(i==VNFTypeSC.size())
						isAllocated=true;
										
				}
				
					if(isAllocated)
						break;
				}
				
				//changing processing time				
				for(WVnfInstance vnfinstance: wNet.getVnfInstances())
				{
					double utilization= vnfinstance.getOccupiedCapacityInGbps()/ vnfinstance.getCurrentCapacityInGbps();
					if(utilization>1) utilization=1;
					System.out.println("utilization"+utilization);
					double newProcessingTime= vnfinstance.getProcessingTimeInMs()+ 10*utilization;
					vnfinstance.setProcessingTimeInMs(newProcessingTime);
				
				}
				/* Dimension the VNF instances, consuming the resources CPU, HD, RAM */
				for(WVnfInstance vnfs: wNet.getVnfInstances()) 
					vnfs.scaleVnfCapacityAndConsumptionToBaseInstanceMultiple();			
			}//end of for cycle VNF of sc

		/*remove VNFs not used*/

		for(WVnfInstance vnfs: wNet.getVnfInstances()) {
			if(vnfs.getOccupiedCapacityInGbps()==0)
				vnfs.remove();
		}
		
		System.out.println("Provisioned a Service Chain with index"+ round);
		round++;
		}

		return "Ok";
	}

	@Override
	public String getDescription()
	{
		return "An algorithm to perform dynamic VNF placement developed by Leila Askari";
	}

	@Override
	public List<Triple<String, String, String>> getParameters()
	{
		return InputParameter.getInformationAllInputParameterFieldsOfObject(this);
	}
}
