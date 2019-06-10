//
//  SwiftUIView.swift
//  ios-app
//
//  Created by Thomas Vos on 10/06/2019.
//

import SwiftUI

struct SwiftUIView : View {
    var body: some View {
        Text("Hello World!")
    }
}

#if DEBUG
struct SwiftUIView_Previews : PreviewProvider {
    static var previews: some View {
        SwiftUIView()
    }
}
#endif
