# Overview

SimpleDNS is a simple DNS server. It recursively resolves DNS requests. SimpleDNS supports NS, A, AAAA, and CNAME DNS requests. The server runs on port 8053.

# How to run
	make

# How to test

Once the server is running, open another terminal. To query the server you can use dig:
	
	dig localhost:8053 <domain> [request type]

By default, dig uses a request type of A.

Examples:

	dig localhost:8053 google.com
	dig localhost:8053 www.yahoo.com CNAME

# Additional Functionality
SimpleDNS will return the entire path taken to resolve a domain in the answer section of the DNS response packet. Also, SimpleDNS will identify all EC2 servers matched from the file ec2.csv in the response packet.

SimpleDNS can also be invoked with two arguments (both are required):

 	make run root=<DNS root server> csv=<csv file>

DNS root server: is the DNS root server that will be used as the initial starting point for DNS resolution. By default 198.41.0.4 (a.root-servers.net) is used.   

csv file: is the csv file of EC2 servers to be identified in the DNS reponse. By default ec2.csv is used.

# Assumptions
The csv file does not have improperly formated lines, lines with commas as values, or a newline at the end.
