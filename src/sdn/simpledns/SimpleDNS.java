package sdn.simpledns;
import sdn.simpledns.packet.*;

import java.util.ArrayList;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.lang.Thread;
import java.util.concurrent.LinkedBlockingQueue;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.lang.ExceptionInInitializerError;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;

public class SimpleDNS {
	private static InetAddress rootServerIp;
	private static List<List<String>> csv;
	private static final int len = 1024;
	// socket for server to receive responses to our own queries on
	private static final DatagramSocket responseSocket;
	// queue to put recieved query responses in
	private static final LinkedBlockingQueue<DNS> inbound = new LinkedBlockingQueue<DNS>();

	private static ArrayList<DNSResourceRecord> additionals = new ArrayList<DNSResourceRecord>();
	private static ArrayList<DNSResourceRecord> authorities = new ArrayList<DNSResourceRecord>();

	// static block to initialize the responseSocket for receiving responses
	static {
		try {
			responseSocket = new DatagramSocket(8054);
		}
		catch (Exception ex) {
			ex.printStackTrace();
			throw new ExceptionInInitializerError();
		}
	}

	// driver
	public static void main(String[] args) throws SocketException {
		// *********************parsing args************************
		try {
			rootServerIp = InetAddress.getByName(args[1]);
		}
		catch (Exception ex) {
			// this exception should never happen because literal IP is given
			System.out.println(ex);
			System.exit(1);
		}
		readCsvIntoMem(args[3]);
		// *********************************************************

		// listening on port 8053 for client requests
		try (DatagramSocket socket = new DatagramSocket(8053);) {
	
			// listenting on port 8054 for responses to our queries
			startListening();

			System.out.println("Listening on port 8053 for client requests...");

			while (true) {
				byte[] buf = new byte[len];
				DatagramPacket packet = new DatagramPacket(buf, len);

				socket.receive(packet);

				DNS dnsPacket = DNS.deserialize(packet.getData(), packet.getLength());

				// only handle opcode 0 packets
				if (dnsPacket.getOpcode() != (byte) 0) {
					continue;
				}

				DNS response = null;
				// if recursion desired begin recursive resolution
				if (dnsPacket.isRecursionDesired()) {
					response = resolve(dnsPacket, null);
				}
				else { // otherwise just query root server
					response = queryServer(dnsPacket, rootServerIp);
				}
				response.setId(dnsPacket.getId());
				response.setRecursionAvailable(true);

				addEc2(response);

				// adding relevant additional and authority RRs found to response
				addAdditionalsAndAuthorities(response);


				// sending the resolved query to client
				byte [] responseBytes = response.serialize();
				InetAddress returnAddress = ((InetSocketAddress) packet.getSocketAddress()).getAddress();
				int returnPort = ((InetSocketAddress) packet.getSocketAddress()).getPort();

				DatagramPacket responseDatagram = new DatagramPacket(responseBytes, response.getLength(), returnAddress, returnPort);
				socket.send(responseDatagram);
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
			System.exit(1);
		}
		finally {
			responseSocket.close();
		}
	}


	private static void addAdditionalsAndAuthorities(DNS dnsPacket) {
		DNSQuestion queryQuestion = (new ArrayList<DNSQuestion>(dnsPacket.getQuestions())).get(0);

	    ArrayList<DNSResourceRecord> answerRRs = new ArrayList<DNSResourceRecord>(dnsPacket.getAnswers());
		ArrayList<DNSResourceRecord> authorityRRs = new ArrayList<DNSResourceRecord>(dnsPacket.getAuthorities());
		ArrayList<DNSResourceRecord> additionalRRs = new ArrayList<DNSResourceRecord>(dnsPacket.getAdditional());

		DNSResourceRecord answer = null;

		for (DNSResourceRecord answerRR : answerRRs) {
			if (answerRR.getType() == queryQuestion.getType()) {
				answer = answerRR;
			}
		}

		if (answer == null) {
			// reseting the additional and authority lists
			additionals = new ArrayList<DNSResourceRecord>();
			authorities = new ArrayList<DNSResourceRecord>();
			return;
		}

		for (DNSResourceRecord authority : authorities) {
			boolean addedAuth = false;
			if (authority.getType() == DNS.TYPE_NS && authority.getName().equals(answer.getName())) {
				// we have a relevant authority
				// check if it is already added to dnsPacket
				for (DNSResourceRecord authorityRR : authorityRRs) {
					if (authorityRR.toString().equals(authority.toString())) {
						addedAuth = true;
						break;
					}
				}

				if (!addedAuth) {
					authorityRRs.add(authority);

					String authData = ((DNSRdataName) authority.getData()).getName();

					// if there is a matching additional add it as well
					for (DNSResourceRecord additional : additionals) {
						if (!additional.getName().equals(authData)) {
							continue;
						}


						if (additional.getType() == DNS.TYPE_A || additional.getType() == DNS.TYPE_AAAA) {
							boolean added = false;
							for (DNSResourceRecord additionalRR : additionalRRs) {
								if (additionalRR.toString().equals(additional.toString())) {
									added = true;
									break;
								}
							}

							if (!added) {
								additionalRRs.add(additional);
							}
						}
					}
				}
			}
		}


		dnsPacket.setAuthorities(authorityRRs);
		dnsPacket.setAdditional(additionalRRs);

		// reseting the additional and authority lists
		additionals = new ArrayList<DNSResourceRecord>();
		authorities = new ArrayList<DNSResourceRecord>();
	}

	// source: https://www.baeldung.com/java-csv-file-array
	private static void readCsvIntoMem(String name) {
		csv = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(new FileReader(name))) {
			String line;
			while ((line = br.readLine()) != null) {
				String[] values = line.split(",");
				csv.add(Arrays.asList(values));
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
			System.exit(1);
		}
	}

	// soruce: https://stackoverflow.com/questions/12057853/how-to-convert-string-ip-numbers-to-integer-in-java
	private static long ipToInt(String str) {
		long ipNumbers = 0;
		String[] ipParts = str.split("\\.");
		for (int i = 0; i < 4; i++) {
			ipNumbers += Integer.parseInt(ipParts[i]) << (24 - (8 * i));
		}

		return ipNumbers;
	}

	private static void addEc2(DNS dnsPacket) {
		ArrayList<DNSResourceRecord> answers = new ArrayList<DNSResourceRecord>(dnsPacket.getAnswers());
		
		for (DNSResourceRecord answer : answers) {
			if (answer.getType() != DNS.TYPE_A) {
				continue;
			}

			for (List<String> row : csv) {
				long ip = ipToInt(answer.getData().toString());
				String[] subnetAndMask = row.get(0).split("/");

				long subnet = ipToInt(subnetAndMask[0]);
				int maskNumBits = Integer.parseInt(subnetAndMask[1]);
				long mask = 0;

				for (int i = 0; i < maskNumBits; ++i) {
					mask = (mask+1) << 1;
				}

				for (int i = maskNumBits+1; i < 32; ++i) {
					mask = mask << 1;
				}

				if ((ip & mask) == subnet) {
					String str = row.get(1) + "-" + ip;
					DNSRdataString txt = new DNSRdataString(str);
					DNSResourceRecord ec2 = new DNSResourceRecord("EC2", (short) 16, txt);
					dnsPacket.addAnswer(ec2);
				}
			}
		}
	}

	// starts a thread to listen on a port 8054
	// it puts any packets it recieves on that port in a queue
	private static void startListening() {
		Thread listener = new Thread(new Runnable() {
			public void run() {
				try {
					while (true) {
						byte[] buf = new byte[len];
						DatagramPacket packet = new DatagramPacket(buf, len);

						responseSocket.receive(packet);

						DNS dnsPacket = DNS.deserialize(packet.getData(), packet.getLength());

						inbound.put(dnsPacket);
					}
				}
				catch (Exception ex) {
					ex.printStackTrace();
					System.exit(1);
				}
			}
		});

		listener.start();
	}

	private static DNS queryServer(DNS dnsPacket, InetAddress server) {
		DatagramPacket queryPacket = new DatagramPacket(dnsPacket.serialize(), dnsPacket.getLength(), server, 53);
		System.out.println("******************************QUERY*****************************");
		System.out.println("TO: " + server);
		System.out.println(dnsPacket.toString());
		System.out.println("****************************************************************");
		DNS rsp = null;

		try {
			// sends query and waits for response
			responseSocket.send(queryPacket);
			rsp = inbound.poll(1000, TimeUnit.MILLISECONDS);
			if (rsp == null) {
				throw new TimeoutException();
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
			System.exit(1);
		}

		System.out.println("****************************RESPONSE****************************");
		System.out.println(rsp.toString());
		System.out.println("****************************************************************");

		return rsp;
	}

	/**
	 * @param query the query from the client that is being recursively resolved
	 * @param responsePacket the current response packet being checked for the answer,
	 * 						 if it is null, the root DNS server will be queried for one
	 * @param additionals all of the additional RRs encountered during the resolution
	 * @param authorities all of the authority RRs encountered during the resolution
	 * @return the DNS packet with the answer to the query in it
	 */
	private static DNS resolve(DNS query, DNS responsePacket) {
		Random rand = new Random();
		if (responsePacket == null) {
			responsePacket = queryServer(query, rootServerIp);
		}

		// getting the Resource Records for the current response
		DNSQuestion queryQuestion = (new ArrayList<DNSQuestion>(query.getQuestions())).get(0);
		ArrayList<DNSResourceRecord> answerRRs = new ArrayList<DNSResourceRecord>(responsePacket.getAnswers());
		ArrayList<DNSResourceRecord> authorityRRs = new ArrayList<DNSResourceRecord>(responsePacket.getAuthorities());
		ArrayList<DNSResourceRecord> additionalRRs = new ArrayList<DNSResourceRecord>(responsePacket.getAdditional());

		// saving all additional and authority RRs found
		additionals.addAll(additionalRRs);
		authorities.addAll(authorityRRs);

		// checking if answer to query has been found
		for (DNSResourceRecord answer : answerRRs) {
			// if response matches query, we have found the answer, just return
			if (answer.getName().equals(queryQuestion.getName()) && answer.getType() == queryQuestion.getType()) {
				return responsePacket;
			}
			// useless server gave me a CNAME record as an answer
			else if (answer.getType() == DNS.TYPE_CNAME) {
				String cname = ((DNSRdataName) answer.getData()).getName();

				// creating new query to find IP of cname
				DNS subQuery = new DNS();
				DNSQuestion question = new DNSQuestion(cname, DNS.TYPE_A);
				subQuery.addQuestion(question);
				subQuery.setRecursionDesired(true);
				subQuery.setQuery(true);
				subQuery.setRecursionAvailable(false);
				subQuery.setId((short) rand.nextInt());

				// recursively resolve cname's IP
				DNS subResponse = resolve(subQuery, null);

				// we have the cname's IP now
				ArrayList<DNSResourceRecord> subResponseAnswers = new ArrayList<DNSResourceRecord>(subResponse.getAnswers());
	 			for (DNSResourceRecord subResponseAnswer : subResponseAnswers) {

					// prevent duplicate records
					boolean inAnswers = false;
					for (DNSResourceRecord a : answerRRs) {
						if (a.getData().toString().equals(subResponseAnswer.getData().toString())) {
							inAnswers = true;
							break;
						}
					}

					if (!inAnswers) {
						responsePacket.addAnswer(subResponseAnswer);
					}
				}

				return responsePacket;
			}
		}

		// if there is no satisfactory answer continue recursing
		for (DNSResourceRecord authority : authorityRRs) {
			if (authority.getType() != DNS.TYPE_NS) {
				continue;
			}

			String nextServerToQuery = ((DNSRdataName) authority.getData()).getName();

			// if there is a matching additional we can just use it
			for (DNSResourceRecord additional : additionalRRs) {
				if (!additional.getName().equals(nextServerToQuery)) {
					continue;
				}
				if (additional.getType() == DNS.TYPE_A || additional.getType() == DNS.TYPE_AAAA) {
					InetAddress nextAddr = ((DNSRdataAddress) additional.getData()).getAddress();
					responsePacket = queryServer(query, nextAddr);
					return resolve(query, responsePacket);
				}
			}

			// if there are no additional RRs, will have to query again using authority RR
			// creating new query to find IP of next server to query
			DNS subQuery = new DNS();
			DNSQuestion question = new DNSQuestion(nextServerToQuery, DNS.TYPE_A);
			subQuery.addQuestion(question);
			subQuery.setRecursionDesired(true);
			subQuery.setQuery(true);
			subQuery.setRecursionAvailable(false);
			subQuery.setId((short) rand.nextInt());

			// recursively resolve authority's IP
			DNS subResponse = resolve(subQuery, null);
			DNSResourceRecord subResponseAnswer = (new ArrayList<DNSResourceRecord>(subResponse.getAnswers())).get(0);

			// we have the authority's IP now
			InetAddress nextAddr = ((DNSRdataAddress) subResponseAnswer.getData()).getAddress();
			responsePacket = queryServer(query, nextAddr);
			return resolve(query, responsePacket);
		}

		// if we reach here something has gone horribly wrong, refuse the request
		query.setRcode((byte) 5);
		query.setQuery(false);
		return query;
	}
}
