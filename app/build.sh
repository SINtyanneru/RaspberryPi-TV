mkdir -p build/app/

(cd app/partymode/ && mvn -q package)

mkdir -p build/app/partymode/
cp app/partymode/target/partymode-1.0-full.jar build/app/partymode/main.jar
cp app/partymode/meta.txt build/app/partymode/meta.txt