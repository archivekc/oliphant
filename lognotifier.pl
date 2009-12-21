use warnings;
use strict;
use SQL::Statement;
use File::Tail;

my $tables; # Maps table names to arrays of pk column names
$tables->{'OBJET_PERSISTANT'} = qw/ID/;

my $parser = SQL::Parser->new();

tie *FILE,"File::Tail",(name=>@ARGV[0]);
while <FILE>
	{
	if ($_ !~ /^(DELETE|UPDATE)/) { continue; }
	my $stmt = SQL::Statement->new($_, $parser);
	my $table = $stmt->tables(0); # There will always be a single table for a DELETE or UPDATE statement
	if (!$tables->{$table}) { continue; }
	my $where = $stmt->where();
	if (!$where) { continue; } # Whole table update -> useless ?
	$pk = false; # Notifications will be limited to where clauses we can parse and get the PK from
	$op = $where->op();
	if ($op == '=') {
	my @columns = $stmt->columns(); 
	my @values = $stmt->row_values(); 
	}
