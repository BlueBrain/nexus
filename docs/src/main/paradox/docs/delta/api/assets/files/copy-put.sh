curl -X PUT \
   -H "Content-Type: application/json" \
   "http://localhost:8080/v1/files/myorg/myproject/newfileid?storage=remote" -d \
   '{
      "destinationFilename": "newfile.pdf",
      "sourceProjectRef": "otherorg/otherproj",
      "sourceFileId": "oldfileid",
      "sourceTag": "mytag",
      "sourceRev": null
  }'