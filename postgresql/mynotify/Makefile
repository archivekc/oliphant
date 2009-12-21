all: 
	gcc -fpic -I /usr/include/postgresql/server -c mynotify.c
	gcc -shared -o mynotify.so mynotify.o
