#include <postgres.h>
#include <fmgr.h>
#include <fcntl.h>
 
PG_MODULE_MAGIC;

int notify(char *notif)
	{
	int fp = open("/var/lib/postgres/my_notify", O_RDWR | O_APPEND);

	if(fp < 0)
		{
		perror("failed to open notification file");
		return EXIT_FAILURE;
		}

	write(fp, notif, strlen(notif));
	write(fp, "\n", 1);
	close(fp);
	return EXIT_SUCCESS;
	}
