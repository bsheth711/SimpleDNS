compile: clean
	find ./src -name "*.java" | xargs javac

run: compile
	java -cp ./src sdn.simpledns.SimpleDNS -r 198.41.0.4 -e ./ec2.csv

debug: compile
	jdb -classpath ./src sdn.simpledns.SimpleDNS -r 198.41.0.4 -e ./ec2.csv

clean:
	find ./src -name "*.class" | xargs rm -rf