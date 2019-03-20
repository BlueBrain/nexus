#Load movies.csv
$ for i in *.txt; do nexus create resource -f ~/ml-latest-small/movies.csv "hello $i"; done