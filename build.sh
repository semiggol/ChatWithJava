rm -rf bin; mkdir bin
javac -d ./bin -cp ./bin jang/common/*.java 
javac -d ./bin -cp ./bin jang/server/*.java 
javac -d ./bin -cp ./bin jang/client/*.java 
