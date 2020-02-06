cd build/libs
rd /s /q mappings
java -cp enigma-0.14.3.jar cuchaz.enigma.CommandMain convert-mappings proguard ../../server.txt enigma mappings
cd ../../
pause
