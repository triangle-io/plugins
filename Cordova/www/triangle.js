var exec = require('cordova/exec');
var cordova = require('cordova');

var triangle =
{
    /**
     * Initializes the Triangle session and exchanges private cryptographic keys with the Triangle servers.
     * @param applicationId ID of the application as defined in your account dashboard at http://www.triangle.io
     * @param accessKey Your account's access key as defined in your account dashboard at http://www.triangle.io
     * @param secretKey Your account's secret key as defined in your account dashboard at http://www.triangle.io
     * @param successCallback A success function will be called if initialization succeeds.
     * @param errorCallback An error callback which will be called if an issue arises during initialization.
     */
    initialize: function(applicationId, accessKey, secretKey, successCallback, errorCallback)
    {
        // Define document events used by the API
        cordova.addDocumentEventHandler('ontaperror');
        cordova.addDocumentEventHandler('ontapsuccess');
        cordova.addDocumentEventHandler('ontapdetect');

        // Call the Android side to initialize the Triangle session
        exec(successCallback, errorCallback, "Triangle", "initialize", [applicationId, accessKey, secretKey]);
    },
    /**
     * Instructs the plugin to decrypt the information in the card. Use with caution.
     * @param modulus Modulus parameter of the RSA's private key for decryption.
     * @param d D parameter of the RSA's private key for decryption.
     */
    decrypt: function(modulus, d, successCallback, errorCallback)
    {
        // Call decrypt method on the plugin
        exec(successCallback, errorCallback, "Triangle", "decrypt", [modulus, d]);
    },
    /**
     * Instructs the API to send card's information in encrypted format
     */
    encrypt: function()
    {
        // Call encrypt method on the plugin
        exec(successCallback, errorCallback, "Triangle", "encrypt");
    }
}

module.exports = triangle;
