/*******************************************************************************

   Copyright (C) 2009-1010 Key Consulting

   This file is part of Oliphant.
 
   Oliphant is free software: you can redistribute it and/or modify
   it under the terms of the GNU Lesser General Public License as published
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.
 
   Oliphant is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU Lesser General Public License for more details.
 
   You should have received a copy of the GNU Lesser General Public
   License along with Oliphant.  If not, see <http://www.gnu.org/licenses/>.

*******************************************************************************/

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
