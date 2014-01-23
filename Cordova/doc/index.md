This plugin exposes its functionality through navigator.triangle.

To use the API, first create your application keys at http://triangle.io. Once you obtain your keys, use the initialize method passing the application ID, access key and secret keys that you obtain.

After initialization, 3 events will be raised on the document object:

- ontapsuccess: When a new card is tapped and processed by the API
- ontapdetect: When a new card is being detected
- ontaperror: When an error occurs during processing of a contactless card

To subscribe to any of the events, use document.addEventListener.

To see the API used in a complete sample, check https://github.com/triangle-io/applications
